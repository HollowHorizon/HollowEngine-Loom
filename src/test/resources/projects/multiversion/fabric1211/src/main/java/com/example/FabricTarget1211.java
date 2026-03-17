package com.example;

import net.minecraft.util.math.BlockPos;

public final class FabricTarget1211 {
	public int packedVersion() {
		return Constants.MINECRAFT_VERSION;
	}

	public boolean isTargetVersion() {
		return Constants.is(1211) && Constants.isAtLeast(1211);
	}

	public int x() {
		return new BlockPos(4, 5, 6).getX();
	}
}
