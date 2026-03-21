package com.example;
import net.minecraft.util.math.BlockPos;

public final class FabricTarget {
	public int packedVersion() {
		return Constants.MINECRAFT_VERSION;
	}

	public boolean isTargetVersion() {
		return Constants.MINECRAFT == Constants.V1_20_1 && Constants.MINECRAFT <= Constants.V1_20_1;
	}

	public int x() {
		return new BlockPos(1, 2, 3).getX();
	}
}
