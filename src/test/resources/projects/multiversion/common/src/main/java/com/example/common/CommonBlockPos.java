package com.example.common;

import com.example.Constants;
import net.minecraft.util.math.BlockPos;

public final class CommonBlockPos {
	public BlockPos create() {
		return new BlockPos(1, 2, 3);
	}

	public void test() {
		if (Constants.is(1211)) {
			BlockPos.max(create(), create());
		}
	}
}
