package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.LinkedHashMap;

import org.junit.Assert;
import org.junit.Test;

public class MultiversionMetadataResolverTest {
	@Test
	public void detectsSyntheticMethodAliases() {
		MultiversionApiMetadataModel metadata = new MultiversionApiMetadataModel();
		metadata.syntheticMethodLookup = new LinkedHashMap<>();
		metadata.syntheticMethodLookup.put("com/example/Foo#getId$1_20_1()Lcom/example/MapId;", "com/example/Foo#getId()I");

		Assert.assertTrue(MultiversionMetadataResolver.isSyntheticAlias(metadata.syntheticMethodLookup, "com/example/Foo#getId$1_20_1()Lcom/example/MapId;"));
		Assert.assertFalse(MultiversionMetadataResolver.isSyntheticAlias(metadata.syntheticMethodLookup, "com/example/Foo#getId()I"));
	}

	@Test
	public void detectsSyntheticFieldAliases() {
		MultiversionApiMetadataModel metadata = new MultiversionApiMetadataModel();
		metadata.syntheticFieldLookup = new LinkedHashMap<>();
		metadata.syntheticFieldLookup.put("com/example/Foo#value$1_20_1:Lcom/example/MapId;", "com/example/Foo#value:I");

		Assert.assertTrue(MultiversionMetadataResolver.isSyntheticAlias(metadata.syntheticFieldLookup, "com/example/Foo#value$1_20_1:Lcom/example/MapId;"));
		Assert.assertFalse(MultiversionMetadataResolver.isSyntheticAlias(metadata.syntheticFieldLookup, "com/example/Foo#value:I"));
	}
}
