package net.srddssrf.samesizereturnportal.mixin;

import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

@Mixin(PortalForcer.class)
public class PortalForcerMixin implements ITeleporter {
    @Shadow protected final ServerLevel level;
    @Shadow private boolean canPortalReplaceBlock(BlockPos.MutableBlockPos p_248971_) {throw new AssertionError();};

    public PortalForcerMixin(ServerLevel p_77650_) {
        this.level = p_77650_;
    }

    @Overwrite
    public Optional<BlockUtil.FoundRectangle> createPortal(final BlockPos origin, final Direction.Axis portalAxis) {
        // We are given the coordinate where the return portal should be spawned
        // First find the entry portal so that we can determine its size
        // TODO: in hindsight it might be nicer to also patch NetherPortalBlock.getExitPortal where it calls createPortal
        //  the entry portal is already known there and could just be added as a parameter to this function
        //  instead of having to search back which one it was (and potentially even getting the wrong one)
        ResourceKey<Level> origDimension = this.level.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        ServerLevel originLevel = ServerLifecycleHooks.getCurrentServer().getLevel(origDimension);
        boolean fromNether = originLevel.dimension() == Level.NETHER;
        WorldBorder fromWorldBorder = originLevel.getWorldBorder();
        double teleportationScale = DimensionType.getTeleportationScale(this.level.dimensionType(), originLevel.dimensionType());
        BlockPos approximateEntryPos = fromWorldBorder.clampToBounds(origin.getX() * teleportationScale, origin.getY(), origin.getZ() * teleportationScale);
        BlockUtil.FoundRectangle originPortal = originLevel.getPortalForcer().findPortalAround(approximateEntryPos, fromNether, fromWorldBorder).get();

        int pw = originPortal.axis1Size;
        int ph = originPortal.axis2Size;

        Direction direction = Direction.get(Direction.AxisDirection.POSITIVE, portalAxis);
        double closestFullDistanceSqr = -1.0;
        BlockPos closestFullPosition = null;
        double closestPartialDistanceSqr = -1.0;
        BlockPos closestPartialPosition = null;
        WorldBorder worldBorder = this.level.getWorldBorder();
        int maxPlaceableY = Math.min(this.level.getMaxBuildHeight(), this.level.getMinBuildHeight() + this.level.getLogicalHeight() - 1);
        int edgeDistance = 1;
        BlockPos.MutableBlockPos mutable = origin.mutable();

        for (BlockPos.MutableBlockPos columnPos : BlockPos.spiralAround(origin, 16, Direction.EAST, Direction.SOUTH)) {
            int height = Math.min(maxPlaceableY, this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, columnPos.getX(), columnPos.getZ()));
            if (worldBorder.isWithinBounds(columnPos) && worldBorder.isWithinBounds(columnPos.move(direction, 1))) {
                columnPos.move(direction.getOpposite(), 1);

                for (int y = height; y >= this.level.getMinBuildHeight(); y--) {
                    columnPos.setY(y);
                    if (this.canPortalReplaceBlock(columnPos)) {
                        int firstEmptyY = y;

                        while (y > this.level.getMinBuildHeight() && this.canPortalReplaceBlock(columnPos.move(Direction.DOWN))) {
                            y--;
                        }

                        if (y + ph+1 <= maxPlaceableY) {
                            int deltaY = firstEmptyY - y;
                            if (deltaY <= 0 || deltaY >= ph-2) {
                                columnPos.setY(y);
                                if (this.canHostFrame(columnPos, mutable, direction, 0, pw, ph)) {
                                    double distance = origin.distSqr(columnPos);
                                    if (this.canHostFrame(columnPos, mutable, direction, -1, pw+2, ph+2)
                                            && this.canHostFrame(columnPos, mutable, direction, 1, pw+2, ph+2)
                                            && (closestFullDistanceSqr == -1.0 || closestFullDistanceSqr > distance)) {
                                        closestFullDistanceSqr = distance;
                                        closestFullPosition = columnPos.immutable();
                                    }

                                    if (closestFullDistanceSqr == -1.0 && (closestPartialDistanceSqr == -1.0 || closestPartialDistanceSqr > distance)) {
                                        closestPartialDistanceSqr = distance;
                                        closestPartialPosition = columnPos.immutable();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (closestFullDistanceSqr == -1.0 && closestPartialDistanceSqr != -1.0) {
            closestFullPosition = closestPartialPosition;
            closestFullDistanceSqr = closestPartialDistanceSqr;
        }

        if (closestFullDistanceSqr == -1.0) {
            int minStartY = Math.max(this.level.getMinBuildHeight() - -1, 70);
            int maxStartY = maxPlaceableY - ph - 6;
            if (maxStartY < minStartY) {
                return Optional.empty();
            }

            closestFullPosition = new BlockPos(
                    origin.getX() - direction.getStepX() * 1, Mth.clamp(origin.getY(), minStartY, maxStartY), origin.getZ() - direction.getStepZ() * 1
            )
                    .immutable();
            if (!worldBorder.isWithinBounds(closestFullPosition)) {
                return Optional.empty();
            }
            Direction clockWise = direction.getClockWise();

            for (int box = -1; box < 2; box++) {
                for (int width = 0; width < pw; width++) {
                    for (int height = -1; height < ph; height++) {
                        BlockState blockState = height < 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        mutable.setWithOffset(
                                closestFullPosition,
                                width * direction.getStepX() + box * clockWise.getStepX(),
                                height,
                                width * direction.getStepZ() + box * clockWise.getStepZ()
                        );
                        this.level.setBlockAndUpdate(mutable, blockState);
                    }
                }
            }
        }

        for (int width = -1; width < pw+1; width++) {
            for (int height = -1; height < ph+1; height++) {
                if (width == -1 || width == pw || height == -1 || height == ph) {
                    mutable.setWithOffset(closestFullPosition, width * direction.getStepX(), height, width * direction.getStepZ());
                    this.level.setBlock(mutable, Blocks.OBSIDIAN.defaultBlockState(), 3);
                }
            }
        }

        BlockState portalBlockState = (BlockState)Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, portalAxis);

        for (int width = 0; width < pw; width++) {
            for (int heightx = 0; heightx < ph; heightx++) {
                mutable.setWithOffset(closestFullPosition, width * direction.getStepX(), heightx, width * direction.getStepZ());
                this.level.setBlock(mutable, portalBlockState, 18);
            }
        }

        return Optional.of(new BlockUtil.FoundRectangle(closestFullPosition.immutable(), pw, ph));
    }

    private boolean canHostFrame(BlockPos origin, BlockPos.MutableBlockPos mutable, Direction direction, int offset, int width, int height) {
        Direction clockWise = direction.getClockWise();

        for(int i = -1; i < width-1; ++i) {
            for(int j = -1; j < height-1; ++j) {
                mutable.setWithOffset(origin, direction.getStepX() * i + clockWise.getStepX() * offset, j, direction.getStepZ() * i + clockWise.getStepZ() * offset);
                if (j < 0 && !this.level.getBlockState(mutable).isSolid()) {
                    return false;
                }

                if (j >= 0 && !this.canPortalReplaceBlock(mutable)) {
                    return false;
                }
            }
        }

        return true;
    }
}
