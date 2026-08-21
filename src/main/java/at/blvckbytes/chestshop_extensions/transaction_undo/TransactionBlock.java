package at.blvckbytes.chestshop_extensions.transaction_undo;

import at.blvckbytes.chestshop_extensions.ShopDataListener;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;

import java.util.UUID;

public class TransactionBlock {

  public final UUID worldId;
  public final Block signBlock;

  private final int x, y, z;

  private TransactionBlock(Block signBlock, Block block) {
    this.signBlock = signBlock;
    this.worldId = block.getWorld().getUID();
    this.x = block.getX();
    this.y = block.getY();
    this.z = block.getZ();
  }

  public static TransactionBlock fromSign(Sign sign) {
    var signBlock = sign.getBlock();
    var signType = signBlock.getType();

    var mountFace = BlockFace.DOWN;

    if (Tag.WALL_SIGNS.isTagged(signType))
      mountFace = ((Directional) signBlock.getBlockData()).getFacing().getOppositeFace();

    var mountBlock = signBlock.getRelative(mountFace);

    // For non-container-shops, the identifier remains at the sign itself, as multiple signs could
    // be mounted on the same block (imagine isles separated by a single-block wall).
    if (!(mountBlock.getState(false) instanceof Container))
      return new TransactionBlock(signBlock, signBlock);

    // LEFT or SINGLE are by definition the ones we take as an identifier, such that if we encounter
    // RIGHT, we try to instead use the other chest-half.
    if (mountBlock.getBlockData() instanceof Chest chest && chest.getType() == Chest.Type.RIGHT) {
      var otherHalf = ShopDataListener.tryGetOtherChestHalf(mountBlock);

      if (otherHalf != null)
        return new TransactionBlock(signBlock, otherHalf);
    }

    return new TransactionBlock(signBlock, mountBlock);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof TransactionBlock otherBlock))
      return false;

    if (!worldId.equals(otherBlock.worldId))
      return false;

    return x == otherBlock.x && y == otherBlock.y && z == otherBlock.z;
  }
}
