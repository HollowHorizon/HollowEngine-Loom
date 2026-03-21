package com.example.common;

import com.example.Constants;
import net.minecraft.util.math.BlockPos;

public final class CommonBlockPos {
	public BlockPos create() {
		return new BlockPos(1, 2, 3);
	}

	public void test() {
		if (Constants.MINECRAFT == Constants.V1_21_1) {
			BlockPos.max(create(), create());
		}
	}
}
