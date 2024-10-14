/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.service.accord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import accord.api.Result;
import accord.local.Cleanup;
import accord.local.Command;
import accord.local.CommonAttributes;
import accord.local.DurableBefore;
import accord.local.RedundantBefore;
import accord.local.StoreParticipants;
import accord.primitives.Ballot;
import accord.primitives.PartialDeps;
import accord.primitives.PartialTxn;
import accord.primitives.Route;
import accord.primitives.SaveStatus;
import accord.primitives.Status;
import accord.primitives.Timestamp;
import accord.primitives.TxnId;
import accord.primitives.Writes;
import accord.utils.Invariants;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.journal.Journal;
import org.apache.cassandra.service.accord.serializers.CommandSerializers;
import org.apache.cassandra.service.accord.serializers.DepsSerializer;
import org.apache.cassandra.service.accord.serializers.WaitingOnSerializer;
import org.apache.cassandra.utils.Throwables;

import static accord.local.Cleanup.NO;
import static accord.local.Cleanup.TRUNCATE_WITH_OUTCOME;
import static accord.primitives.Known.KnownDeps.DepsErased;
import static accord.primitives.Known.KnownDeps.DepsUnknown;
import static accord.primitives.Known.KnownDeps.NoDeps;
import static accord.primitives.SaveStatus.TruncatedApplyWithOutcome;
import static accord.primitives.Status.Durability.NotDurable;
import static accord.utils.Invariants.illegalState;
import static org.apache.cassandra.service.accord.SavedCommand.Fields.DURABILITY;
import static org.apache.cassandra.service.accord.SavedCommand.Fields.EXECUTE_AT;
import static org.apache.cassandra.service.accord.SavedCommand.Fields.PARTICIPANTS;
import static org.apache.cassandra.service.accord.SavedCommand.Fields.SAVE_STATUS;
import static org.apache.cassandra.service.accord.SavedCommand.Fields.WRITES;
import static org.apache.cassandra.service.accord.SavedCommand.Load.ALL;

public class SavedCommand
{
    // This enum is order-dependent
    public enum Fields
    {
        PARTICIPANTS, // stored first so we can index it
        SAVE_STATUS,
        PARTIAL_DEPS,
        EXECUTE_AT,
        EXECUTES_AT_LEAST,
        DURABILITY,
        ACCEPTED,
        PROMISED,
        WAITING_ON,
        PARTIAL_TXN,
        WRITES,
        CLEANUP,
        ;

        public static final Fields[] FIELDS = values();
    }

    // TODO: maybe rename this and enclosing classes?
    public static class DiffWriter implements Journal.Writer
    {
        private final Command before;
        private final Command after;
        private final TxnId txnId;

        // TODO: improve encapsulationd
        @VisibleForTesting
        public DiffWriter(Command before, Command after)
        {
            this(after.txnId(), before, after);
        }

        @VisibleForTesting
        public DiffWriter(TxnId txnId, Command before, Command after)
        {
            this.txnId = txnId;
            this.before = before;
            this.after = after;
        }

        @VisibleForTesting
        public Command before()
        {
            return before;
        }

        @VisibleForTesting
        public Command after()
        {
            return after;
        }

        public void write(DataOutputPlus out, int userVersion) throws IOException
        {
            serialize(before, after, out, userVersion);
        }

        public TxnId key()
        {
            return txnId;
        }
    }

    public static ByteBuffer asSerializedDiff(Command after, int userVersion) throws IOException
    {
        try (DataOutputBuffer out = new DataOutputBuffer())
        {
            diff(null, after).write(out, userVersion);
            return out.asNewBuffer();
        }
    }

    @Nullable
    public static DiffWriter diff(Command original, Command current)
    {
        if (original == current
            || current == null
            || current.saveStatus() == SaveStatus.Uninitialised
            || !anyFieldChanged(original, current))
            return null;
        return new SavedCommand.DiffWriter(original, current);
    }

    // TODO (required): calculate flags once
    private static boolean anyFieldChanged(Command before, Command after)
    {
        int flags = validateFlags(getFlags(before, after));
        return (flags >>> 16) != 0;
    }

    private static int validateFlags(int flags)
    {
        Invariants.checkState(0 == (~(flags >>> 16) & (flags & 0xffff)));
        return flags;
    }
    
    public static void serialize(Command before, Command after, DataOutputPlus out, int userVersion) throws IOException
    {
        int flags = validateFlags(getFlags(before, after));
        out.writeInt(flags);

        int iterable = toIterableSetFields(flags);
        while (iterable != 0)
        {
            Fields field = nextSetField(iterable);
            if (getFieldIsNull(field, flags))
            {
                iterable = unsetIterableFields(field, iterable);
                continue;
            }

            switch (field)
            {
                case EXECUTE_AT:
                    CommandSerializers.timestamp.serialize(after.executeAt(), out, userVersion);
                    break;
                case EXECUTES_AT_LEAST:
                    CommandSerializers.timestamp.serialize(after.executesAtLeast(), out, userVersion);
                    break;
                case SAVE_STATUS:
                    out.writeShort(after.saveStatus().ordinal());
                    break;
                case DURABILITY:
                    out.writeByte(after.durability().ordinal());
                    break;
                case ACCEPTED:
                    CommandSerializers.ballot.serialize(after.acceptedOrCommitted(), out, userVersion);
                    break;
                case PROMISED:
                    CommandSerializers.ballot.serialize(after.promised(), out, userVersion);
                    break;
                case PARTICIPANTS:
                    CommandSerializers.participants.serialize(after.participants(), out, userVersion);
                    break;
                case PARTIAL_TXN:
                    CommandSerializers.partialTxn.serialize(after.partialTxn(), out, userVersion);
                    break;
                case PARTIAL_DEPS:
                    DepsSerializer.partialDeps.serialize(after.partialDeps(), out, userVersion);
                    break;
                case WAITING_ON:
                    Command.WaitingOn waitingOn = getWaitingOn(after);
                    long size = WaitingOnSerializer.serializedSize(after.txnId(), waitingOn);
                    ByteBuffer serialized = WaitingOnSerializer.serialize(after.txnId(), waitingOn);
                    Invariants.checkState(serialized.remaining() == size);
                    out.writeInt((int) size);
                    out.write(serialized);
                    break;
                case WRITES:
                    CommandSerializers.writes.serialize(after.writes(), out, userVersion);
                    break;
                case CLEANUP:
                    throw new IllegalStateException();
            }

            iterable = unsetIterableFields(field, iterable);
        }
    }

    @VisibleForTesting
    static int getFlags(Command before, Command after)
    {
        int flags = 0;

        flags = collectFlags(before, after, Command::executeAt, true, Fields.EXECUTE_AT, flags);
        flags = collectFlags(before, after, Command::executesAtLeast, true, Fields.EXECUTES_AT_LEAST, flags);
        flags = collectFlags(before, after, Command::saveStatus, false, SAVE_STATUS, flags);
        flags = collectFlags(before, after, Command::durability, false, DURABILITY, flags);

        flags = collectFlags(before, after, Command::acceptedOrCommitted, false, Fields.ACCEPTED, flags);
        flags = collectFlags(before, after, Command::promised, false, Fields.PROMISED, flags);

        flags = collectFlags(before, after, Command::participants, true, PARTICIPANTS, flags);
        flags = collectFlags(before, after, Command::partialTxn, false, Fields.PARTIAL_TXN, flags);
        flags = collectFlags(before, after, Command::partialDeps, false, Fields.PARTIAL_DEPS, flags);

        flags = collectFlags(before, after, SavedCommand::getWaitingOn, false, Fields.WAITING_ON, flags);

        flags = collectFlags(before, after, Command::writes, false, WRITES, flags);

        return flags;
    }

    static Command.WaitingOn getWaitingOn(Command command)
    {
        if (command instanceof Command.Committed)
            return command.asCommitted().waitingOn();

        return null;
    }

    private static <OBJ, VAL> int collectFlags(OBJ lo, OBJ ro, Function<OBJ, VAL> convert, boolean allowClassMismatch, Fields field, int flags)
    {
        VAL l = null;
        VAL r = null;
        if (lo != null) l = convert.apply(lo);
        if (ro != null) r = convert.apply(ro);

        if (l == r)
            return flags; // no change

        if (r == null)
            flags = setFieldIsNull(field, flags);

        if (l == null || r == null)
            return setFieldChanged(field, flags);

        assert allowClassMismatch || l.getClass() == r.getClass() : String.format("%s != %s", l.getClass(), r.getClass());

        if (l.equals(r))
            return flags; // no change

        return setFieldChanged(field, flags);
    }

    private static int setFieldChanged(Fields field, int oldFlags)
    {
        return oldFlags | (0x10000 << field.ordinal());
    }

    @VisibleForTesting
    static boolean getFieldChanged(Fields field, int oldFlags)
    {
        return (oldFlags & (0x10000 << field.ordinal())) != 0;
    }

    static int toIterableSetFields(int flags)
    {
        return flags >>> 16;
    }

    static Fields nextSetField(int iterable)
    {
        int i = Integer.numberOfTrailingZeros(Integer.lowestOneBit(iterable));
        return i == 32 ? null : Fields.FIELDS[i];
    }

    static int unsetIterableFields(Fields field, int iterable)
    {
        return iterable & ~(1 << field.ordinal());
    }

    @VisibleForTesting
    static boolean getFieldIsNull(Fields field, int oldFlags)
    {
        return (oldFlags & (1 << field.ordinal())) != 0;
    }

    private static int setFieldIsNull(Fields field, int oldFlags)
    {
        return oldFlags | (1 << field.ordinal());
    }

    private static int unsetFieldIsNull(Fields field, int oldFlags)
    {
        return oldFlags & ~(1 << field.ordinal());
    }

    public enum Load
    {
        ALL(0),
        PURGEABLE(SAVE_STATUS, PARTICIPANTS, DURABILITY, EXECUTE_AT, WRITES),
        MINIMAL(SAVE_STATUS, PARTICIPANTS, EXECUTE_AT);

        final int mask;

        Load(int mask)
        {
            this.mask = mask;
        }

        Load(Fields ... fields)
        {
            int mask = -1;
            for (Fields field : fields)
                mask &= ~(1<< field.ordinal());
            this.mask = mask;
        }
    }

    public static class MinimalCommand
    {
        public final TxnId txnId;
        public final SaveStatus saveStatus;
        public final StoreParticipants participants;
        public final Status.Durability durability;
        public final Timestamp executeAt;
        public final Writes writes;

        public MinimalCommand(TxnId txnId, SaveStatus saveStatus, StoreParticipants participants, Status.Durability durability, Timestamp executeAt, Writes writes)
        {
            this.txnId = txnId;
            this.saveStatus = saveStatus;
            this.participants = participants;
            this.durability = durability;
            this.executeAt = executeAt;
            this.writes = writes;
        }
    }

    public static class Builder
    {
        final int mask;
        int flags;

        TxnId txnId;

        Timestamp executeAt;
        Timestamp executeAtLeast;
        SaveStatus saveStatus;
        Status.Durability durability;

        Ballot acceptedOrCommitted;
        Ballot promised;

        StoreParticipants participants;
        PartialTxn partialTxn;
        PartialDeps partialDeps;

        byte[] waitingOnBytes;
        SavedCommand.WaitingOnProvider waitingOn;
        Writes writes;
        Result result;
        Cleanup cleanup;

        boolean nextCalled;
        int count;

        public Builder(TxnId txnId, Load load)
        {
            this.mask = load.mask;
            init(txnId);
        }

        public Builder(TxnId txnId)
        {
            this(txnId, ALL);
        }

        public Builder(Load load)
        {
            this.mask = load.mask;
        }

        public Builder()
        {
            this(ALL);
        }

        public TxnId txnId()
        {
            return txnId;
        }

        public Timestamp executeAt()
        {
            return executeAt;
        }

        public Timestamp executeAtLeast()
        {
            return executeAtLeast;
        }

        public SaveStatus saveStatus()
        {
            return saveStatus;
        }

        public Status.Durability durability()
        {
            return durability;
        }

        public Ballot acceptedOrCommitted()
        {
            return acceptedOrCommitted;
        }

        public Ballot promised()
        {
            return promised;
        }

        public StoreParticipants participants()
        {
            return participants;
        }

        public PartialTxn partialTxn()
        {
            return partialTxn;
        }

        public PartialDeps partialDeps()
        {
            return partialDeps;
        }

        public SavedCommand.WaitingOnProvider waitingOn()
        {
            return waitingOn;
        }

        public Writes writes()
        {
            return writes;
        }

        public Result result()
        {
            return result;
        }

        public void clear()
        {
            flags = 0;
            txnId = null;

            executeAt = null;
            executeAtLeast = null;
            saveStatus = null;
            durability = null;

            acceptedOrCommitted = null;
            promised = null;

            participants = null;
            partialTxn = null;
            partialDeps = null;

            waitingOnBytes = null;
            waitingOn = null;
            writes = null;
            result = null;
            cleanup = null;

            nextCalled = false;
            count = 0;
        }

        public void reset(TxnId txnId)
        {
            clear();
            init(txnId);
        }

        public void init(TxnId txnId)
        {
            this.txnId = txnId;
            durability = NotDurable;
            acceptedOrCommitted = promised = Ballot.ZERO;
            waitingOn = (txn, deps) -> null;
            result = CommandSerializers.APPLIED;
        }

        public boolean isEmpty()
        {
            return !nextCalled;
        }

        public int count()
        {
            return count;
        }

        public Cleanup shouldCleanup(RedundantBefore redundantBefore, DurableBefore durableBefore)
        {
            if (!nextCalled)
                return NO;

            if (saveStatus == null || participants == null)
                return Cleanup.NO;

            Cleanup cleanup = Cleanup.shouldCleanupPartial(txnId, saveStatus, durability, participants, redundantBefore, durableBefore);
            if (this.cleanup != null && this.cleanup.compareTo(cleanup) > 0)
                cleanup = this.cleanup;
            return cleanup;
        }

        // TODO (expected): avoid allocating new builder
        public Builder maybeCleanup(Cleanup cleanup)
        {
            if (saveStatus() == null)
                return this;

            switch (cleanup)
            {
                case EXPUNGE:
                case ERASE:
                    return null;

                case EXPUNGE_PARTIAL:
                    return expungePartial(cleanup, saveStatus, true);

                case VESTIGIAL:
                case INVALIDATE:
                    return saveStatusOnly();

                case TRUNCATE_WITH_OUTCOME:
                case TRUNCATE:
                    return expungePartial(cleanup, cleanup.appliesIfNot, cleanup == TRUNCATE_WITH_OUTCOME);

                case NO:
                    return this;
                default:
                    throw new IllegalStateException("Unknown cleanup: " + cleanup);}
        }

        public Builder expungePartial(Cleanup cleanup, SaveStatus saveStatus, boolean includeOutcome)
        {
            Invariants.checkState(txnId != null);
            Builder builder = new Builder(txnId, ALL);

            builder.count++;
            builder.nextCalled = true;

            Invariants.checkState(saveStatus != null);
            builder.flags = setFieldChanged(SAVE_STATUS, builder.flags);
            builder.saveStatus = saveStatus;
            builder.flags = setFieldChanged(Fields.CLEANUP, builder.flags);
            builder.cleanup = cleanup;
            if (executeAt != null)
            {
                builder.flags = setFieldChanged(Fields.EXECUTE_AT, builder.flags);
                builder.executeAt = executeAt;
            }
            if (durability != null)
            {
                builder.flags = setFieldChanged(DURABILITY, builder.flags);
                builder.durability = durability;
            }
            if (participants != null)
            {
                builder.flags = setFieldChanged(PARTICIPANTS, builder.flags);
                builder.participants = participants;
            }
            if (includeOutcome && builder.writes != null)
            {
                builder.flags = setFieldChanged(WRITES, builder.flags);
                builder.writes = writes;
            }

            return builder;
        }

        public Builder saveStatusOnly()
        {
            Invariants.checkState(txnId != null);
            Builder builder = new Builder(txnId, ALL);

            builder.count++;
            builder.nextCalled = true;

            // TODO: these accesses can be abstracted away
            if (saveStatus != null)
            {
                builder.flags = setFieldChanged(SAVE_STATUS, builder.flags);
                builder.saveStatus = saveStatus;
            }

            return builder;
        }

        public ByteBuffer asByteBuffer(int userVersion) throws IOException
        {
            try (DataOutputBuffer out = new DataOutputBuffer())
            {
                serialize(out, userVersion);
                return out.asNewBuffer();
            }
        }

        public MinimalCommand asMinimal()
        {
            return new MinimalCommand(txnId, saveStatus, participants, durability, executeAt, writes);
        }

        public static Route<?> deserializeRouteOrNull(DataInputPlus in, int userVersion) throws IOException
        {
            int flags = in.readInt();

            if (!getFieldChanged(PARTICIPANTS, flags) || getFieldIsNull(PARTICIPANTS, flags))
                return null;

            return CommandSerializers.participants.deserializeRouteOnly(in, userVersion);
        }

        public void serialize(DataOutputPlus out, int userVersion) throws IOException
        {
            Invariants.checkState(mask == 0);
            out.writeInt(validateFlags(flags));

            int iterable = toIterableSetFields(flags);
            while (iterable != 0)
            {
                Fields field = nextSetField(iterable);
                if (getFieldIsNull(field, flags))
                {
                    iterable = unsetIterableFields(field, iterable);
                    continue;
                }

                switch (field)
                {
                    case EXECUTE_AT:
                        CommandSerializers.timestamp.serialize(executeAt(), out, userVersion);
                        break;
                    case EXECUTES_AT_LEAST:
                        CommandSerializers.timestamp.serialize(executeAtLeast(), out, userVersion);
                        break;
                    case SAVE_STATUS:
                        out.writeShort(saveStatus().ordinal());
                        break;
                    case DURABILITY:
                        out.writeByte(durability().ordinal());
                        break;
                    case ACCEPTED:
                        CommandSerializers.ballot.serialize(acceptedOrCommitted(), out, userVersion);
                        break;
                    case PROMISED:
                        CommandSerializers.ballot.serialize(promised(), out, userVersion);
                        break;
                    case PARTICIPANTS:
                        CommandSerializers.participants.serialize(participants(), out, userVersion);
                        break;
                    case PARTIAL_TXN:
                        CommandSerializers.partialTxn.serialize(partialTxn(), out, userVersion);
                        break;
                    case PARTIAL_DEPS:
                        DepsSerializer.partialDeps.serialize(partialDeps(), out, userVersion);
                        break;
                    case WAITING_ON:
                        out.writeInt(waitingOnBytes.length);
                        out.write(waitingOnBytes);
                        break;
                    case WRITES:
                        CommandSerializers.writes.serialize(writes(), out, userVersion);
                        break;
                    case CLEANUP:
                        out.writeByte(cleanup.ordinal());
                        break;
                }

                iterable = unsetIterableFields(field, iterable);
            }
        }

        // TODO: we seem to be writing some form of empty transaction
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public void deserializeNext(DataInputPlus in, int userVersion) throws IOException
        {
            Invariants.checkState(txnId != null);
            final int flags = in.readInt();
            nextCalled = true;
            count++;

            int iterable = toIterableSetFields(flags);
            while (iterable != 0)
            {
                Fields field = nextSetField(iterable);
                if (getFieldChanged(field, this.flags) || getFieldIsNull(field, mask))
                {
                    if (!getFieldIsNull(field, flags))
                        skip(field, in, userVersion);

                    iterable = unsetIterableFields(field, iterable);
                    continue;
                }
                this.flags = setFieldChanged(field, this.flags);

                if (getFieldIsNull(field, flags))
                {
                    this.flags = setFieldIsNull(field, this.flags);
                }
                else
                {
                    deserialize(field, in, userVersion);
                }

                iterable = unsetIterableFields(field, iterable);
            }
        }

        private void deserialize(Fields field, DataInputPlus in, int userVersion) throws IOException
        {
            switch (field)
            {
                case EXECUTE_AT:
                    executeAt = CommandSerializers.timestamp.deserialize(in, userVersion);
                    break;
                case EXECUTES_AT_LEAST:
                    executeAtLeast = CommandSerializers.timestamp.deserialize(in, userVersion);
                    break;
                case SAVE_STATUS:
                    saveStatus = SaveStatus.values()[in.readShort()];
                    break;
                case DURABILITY:
                    durability = Status.Durability.values()[in.readByte()];
                    break;
                case ACCEPTED:
                    acceptedOrCommitted = CommandSerializers.ballot.deserialize(in, userVersion);
                    break;
                case PROMISED:
                    promised = CommandSerializers.ballot.deserialize(in, userVersion);
                    break;
                case PARTICIPANTS:
                    participants = CommandSerializers.participants.deserialize(in, userVersion);
                    break;
                case PARTIAL_TXN:
                    partialTxn = CommandSerializers.partialTxn.deserialize(in, userVersion);
                    break;
                case PARTIAL_DEPS:
                    partialDeps = DepsSerializer.partialDeps.deserialize(in, userVersion);
                    break;
                case WAITING_ON:
                    int size = in.readInt();
                    waitingOnBytes = new byte[size];
                    in.readFully(waitingOnBytes);
                    ByteBuffer buffer = ByteBuffer.wrap(waitingOnBytes);
                    waitingOn = (localTxnId, deps) -> {
                        try
                        {
                            Invariants.nonNull(deps);
                            return WaitingOnSerializer.deserialize(localTxnId, deps.keyDeps.keys(), deps.rangeDeps, deps.directKeyDeps, buffer);
                        }
                        catch (IOException e)
                        {
                            throw Throwables.unchecked(e);
                        }
                    };
                    break;
                case WRITES:
                    writes = CommandSerializers.writes.deserialize(in, userVersion);
                    break;
                case CLEANUP:
                    Cleanup newCleanup = Cleanup.forOrdinal(in.readByte());
                    if (cleanup == null || newCleanup.compareTo(cleanup) > 0)
                        cleanup = newCleanup;
                    break;
            }
        }

        private void skip(Fields field, DataInputPlus in, int userVersion) throws IOException
        {
            switch (field)
            {
                case EXECUTE_AT:
                case EXECUTES_AT_LEAST:
                    CommandSerializers.timestamp.skip(in, userVersion);
                    break;
                case SAVE_STATUS:
                    in.readShort();
                    break;
                case DURABILITY:
                    in.readByte();
                    break;
                case ACCEPTED:
                case PROMISED:
                    CommandSerializers.ballot.skip(in, userVersion);
                    break;
                case PARTICIPANTS:
                    CommandSerializers.participants.deserialize(in, userVersion);
                    break;
                case PARTIAL_TXN:
                    CommandSerializers.partialTxn.deserialize(in, userVersion);
                    break;
                case PARTIAL_DEPS:
                    DepsSerializer.partialDeps.deserialize(in, userVersion);
                    break;
                case WAITING_ON:
                    int size = in.readInt();
                    in.skipBytesFully(size);
                    break;
                case WRITES:
                    // TODO (expected): skip
                    CommandSerializers.writes.deserialize(in, userVersion);
                    break;
                case CLEANUP:
                    in.readByte();
                    break;
            }
        }

        public void forceResult(Result newValue)
        {
            this.result = newValue;
        }

        public Command construct()
        {
            if (!nextCalled)
                return null;

            Invariants.checkState(txnId != null);
            CommonAttributes.Mutable attrs = new CommonAttributes.Mutable(txnId);
            if (partialTxn != null)
                attrs.partialTxn(partialTxn);
            if (durability != null)
                attrs.durability(durability);
            if (participants != null)
                attrs.setParticipants(participants);
            if (partialDeps != null &&
                (saveStatus.known.deps != NoDeps &&
                 saveStatus.known.deps != DepsErased &&
                 saveStatus.known.deps != DepsUnknown))
                attrs.partialDeps(partialDeps);

            Command.WaitingOn waitingOn = null;
            if (this.waitingOn != null)
                waitingOn = this.waitingOn.provide(txnId, partialDeps);

            switch (saveStatus.status)
            {
                case NotDefined:
                    return saveStatus == SaveStatus.Uninitialised ? Command.NotDefined.uninitialised(attrs.txnId())
                                                                  : Command.NotDefined.notDefined(attrs, promised);
                case PreAccepted:
                    return Command.PreAccepted.preAccepted(attrs, executeAt, promised);
                case AcceptedInvalidate:
                case Accepted:
                case PreCommitted:
                    if (saveStatus == SaveStatus.AcceptedInvalidate)
                        return Command.AcceptedInvalidateWithoutDefinition.acceptedInvalidate(attrs, promised, acceptedOrCommitted);
                    else
                        return Command.Accepted.accepted(attrs, saveStatus, executeAt, promised, acceptedOrCommitted);
                case Committed:
                case Stable:
                    return Command.Committed.committed(attrs, saveStatus, executeAt, promised, acceptedOrCommitted, waitingOn);
                case PreApplied:
                case Applied:
                    return Command.Executed.executed(attrs, saveStatus, executeAt, promised, acceptedOrCommitted, waitingOn, writes, result);
                case Truncated:
                case Invalidated:
                    return truncated(attrs, saveStatus, executeAt, executeAtLeast, writes, result);
                default:
                    throw new IllegalStateException();
            }
        }

        private static Command.Truncated truncated(CommonAttributes.Mutable attrs, SaveStatus status, Timestamp executeAt, Timestamp executesAtLeast, Writes writes, Result result)
        {
            switch (status)
            {
                default:
                    throw illegalState("Unhandled SaveStatus: " + status);
                case TruncatedApplyWithOutcome:
                case TruncatedApplyWithDeps:
                case TruncatedApply:
                    if (status != TruncatedApplyWithOutcome)
                        result = null;
                    if (attrs.txnId().kind().awaitsOnlyDeps())
                        return Command.Truncated.truncatedApply(attrs, status, executeAt, writes, result, executesAtLeast);
                    return Command.Truncated.truncatedApply(attrs, status, executeAt, writes, result, null);
                case ErasedOrVestigial:
                    return Command.Truncated.erasedOrInvalidOrVestigial(attrs.txnId(), attrs.durability(), attrs.participants());
                case Erased:
                    return Command.Truncated.erased(attrs.txnId(), attrs.durability(), attrs.participants());
                case Invalidated:
                    return Command.Truncated.invalidated(attrs.txnId());
            }
        }

        public String toString()
        {
            return "Diff {" +
                   "txnId=" + txnId +
                   ", executeAt=" + executeAt +
                   ", saveStatus=" + saveStatus +
                   ", durability=" + durability +
                   ", acceptedOrCommitted=" + acceptedOrCommitted +
                   ", promised=" + promised +
                   ", participants=" + participants +
                   ", partialTxn=" + partialTxn +
                   ", partialDeps=" + partialDeps +
                   ", waitingOn=" + waitingOn +
                   ", writes=" + writes +
                   '}';
        }
    }

    public interface WaitingOnProvider
    {
        Command.WaitingOn provide(TxnId txnId, PartialDeps deps);
    }
}