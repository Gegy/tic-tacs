package net.gegy1000.acttwo.chunk.loader.upgrade;

import com.google.common.collect.Sets;
import net.gegy1000.acttwo.Mutability;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.List;
import java.util.Set;

public final class ChunkStatusRequirements {
    // TODO: evaluate this
    private static final Set<ChunkStatus> MUTABLE_CONTEXT = Sets.newHashSet(ChunkStatus.FEATURES, ChunkStatus.LIGHT);

    private static final Mutability[] STATUS_TO_CONTEXT_REQUIREMENT;

    static {
        List<ChunkStatus> statuses = ChunkStatus.createOrderedList();

        STATUS_TO_CONTEXT_REQUIREMENT = new Mutability[statuses.size()];
        for (int i = 0; i < statuses.size(); i++) {
            ChunkStatus status = statuses.get(i);

            Mutability contextMutability = MUTABLE_CONTEXT.contains(status) ? Mutability.MUTABLE : Mutability.IMMUTABLE;
            STATUS_TO_CONTEXT_REQUIREMENT[i] = contextMutability;
        }
    }

    private ChunkStatusRequirements() {
    }

    public static Mutability getContextMutability(ChunkStatus status) {
        return STATUS_TO_CONTEXT_REQUIREMENT[status.getIndex()];
    }
}
