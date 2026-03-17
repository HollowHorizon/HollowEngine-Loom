package com.example;
import net.minecraft.util.math.BlockPos;

public final class FabricTarget {
	public int packedVersion() {
		return Constants.MINECRAFT_VERSION;
	}

	public boolean isTargetVersion() {
		return Constants.is(1201) && Constants.isAtMost(1201);
	}

	public int x() {
		return new BlockPos(1, 2, 3).getX();
	}
}
