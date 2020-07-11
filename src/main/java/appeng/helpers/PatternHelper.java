/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.helpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ICraftingRecipe;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.world.World;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.ContainerNull;
import appeng.core.Api;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;

public class PatternHelper implements ICraftingPatternDetails, Comparable<PatternHelper> {

    private final ItemStack patternItem;
    private final CraftingInventory crafting = new CraftingInventory(new ContainerNull(), 3, 3);
    private final CraftingInventory testFrame = new CraftingInventory(new ContainerNull(), 3, 3);
    private final ItemStack correctOutput;
    private final ICraftingRecipe standardRecipe;
    private final IAEItemStack[] condensedInputs;
    private final IAEItemStack[] condensedOutputs;
    private final IAEItemStack[] inputs;
    private final IAEItemStack[] outputs;
    private final boolean isCrafting;
    private final boolean canSubstitute;
    private final Set<TestLookup> failCache = new HashSet<>();
    private final Set<TestLookup> passCache = new HashSet<>();
    private final IAEItemStack pattern;
    private int priority = 0;

    public PatternHelper(final ItemStack is, final World w) {
        final CompoundNBT encodedValue = is.getTag();

        if (encodedValue == null) {
            throw new IllegalArgumentException("No pattern here!");
        }

        final ListNBT inTag = encodedValue.getList("in", 10);
        final ListNBT outTag = encodedValue.getList("out", 10);
        this.isCrafting = encodedValue.getBoolean("crafting");

        this.canSubstitute = this.isCrafting && encodedValue.getBoolean("substitute");
        this.patternItem = is;
        this.pattern = AEItemStack.fromItemStack(is);

        final List<IAEItemStack> in = new ArrayList<>();
        final List<IAEItemStack> out = new ArrayList<>();

        for (int x = 0; x < inTag.size(); x++) {
            CompoundNBT ingredient = inTag.getCompound(x);
            final ItemStack gs = ItemStack.read(ingredient);

            if (!ingredient.isEmpty() && gs.isEmpty()) {
                throw new IllegalArgumentException("No pattern here!");
            }

            this.crafting.setInventorySlotContents(x, gs);

            if (!gs.isEmpty() && (!this.isCrafting || !gs.hasTag())) {
                this.markItemAs(x, gs, TestStatus.ACCEPT);
            }

            in.add(Api.instance().storage().getStorageChannel(IItemStorageChannel.class).createStack(gs));
            this.testFrame.setInventorySlotContents(x, gs);
        }

        if (this.isCrafting) {
            this.standardRecipe = w.getRecipeManager().getRecipe(IRecipeType.CRAFTING, this.crafting, w).orElse(null);

            if (this.standardRecipe != null) {
                this.correctOutput = this.standardRecipe.getCraftingResult(this.crafting);
                out.add(Api.instance().storage().getStorageChannel(IItemStorageChannel.class)
                        .createStack(this.correctOutput));
            } else {
                throw new IllegalStateException("No pattern here!");
            }
        } else {
            this.standardRecipe = null;
            this.correctOutput = ItemStack.EMPTY;

            for (int x = 0; x < outTag.size(); x++) {
                CompoundNBT resultItemTag = outTag.getCompound(x);
                final ItemStack gs = ItemStack.read(resultItemTag);

                if (!resultItemTag.isEmpty() && gs.isEmpty()) {
                    throw new IllegalArgumentException("No pattern here!");
                }

                if (!gs.isEmpty()) {
                    out.add(Api.instance().storage().getStorageChannel(IItemStorageChannel.class).createStack(gs));
                }
            }
        }

        this.outputs = out.toArray(new IAEItemStack[0]);
        this.inputs = in.toArray(new IAEItemStack[0]);

        final Map<IAEItemStack, IAEItemStack> tmpOutputs = new HashMap<>();

        for (final IAEItemStack io : this.outputs) {
            if (io == null) {
                continue;
            }

            final IAEItemStack g = tmpOutputs.get(io);

            if (g == null) {
                tmpOutputs.put(io, io.copy());
            } else {
                g.add(io);
            }
        }

        final Map<IAEItemStack, IAEItemStack> tmpInputs = new HashMap<>();

        for (final IAEItemStack io : this.inputs) {
            if (io == null) {
                continue;
            }

            final IAEItemStack g = tmpInputs.get(io);

            if (g == null) {
                tmpInputs.put(io, io.copy());
            } else {
                g.add(io);
            }
        }

        if (tmpOutputs.isEmpty() || tmpInputs.isEmpty()) {
            throw new IllegalStateException("No pattern here!");
        }

        this.condensedInputs = new IAEItemStack[tmpInputs.size()];
        int offset = 0;

        for (final IAEItemStack io : tmpInputs.values()) {
            this.condensedInputs[offset] = io;
            offset++;
        }

        offset = 0;
        this.condensedOutputs = new IAEItemStack[tmpOutputs.size()];

        for (final IAEItemStack io : tmpOutputs.values()) {
            this.condensedOutputs[offset] = io;
            offset++;
        }
    }

    private void markItemAs(final int slotIndex, final ItemStack i, final TestStatus b) {
        if (b == TestStatus.TEST || i.hasTag()) {
            return;
        }

        (b == TestStatus.ACCEPT ? this.passCache : this.failCache).add(new TestLookup(slotIndex, i));
    }

    @Override
    public ItemStack getPattern() {
        return this.patternItem;
    }

    @Override
    public synchronized boolean isValidItemForSlot(final int slotIndex, final ItemStack i, final World w) {
        if (!this.isCrafting) {
            throw new IllegalStateException("Only crafting recipes supported.");
        }

        final TestStatus result = this.getStatus(slotIndex, i);

        switch (result) {
            case ACCEPT:
                return true;
            case DECLINE:
                return false;
            case TEST:
            default:
                break;
        }

        for (int x = 0; x < this.crafting.getSizeInventory(); x++) {
            this.testFrame.setInventorySlotContents(x, this.crafting.getStackInSlot(x));
        }

        this.testFrame.setInventorySlotContents(slotIndex, i);

        // If we cannot substitute, the items must match exactly
        if (!canSubstitute && slotIndex < inputs.length) {
            if (!inputs[slotIndex].isSameType(i)) {
                this.markItemAs(slotIndex, i, TestStatus.DECLINE);
                return false;
            }
        }

        if (this.standardRecipe.matches(this.testFrame, w)) {
            final ItemStack testOutput = this.standardRecipe.getCraftingResult(this.testFrame);

            if (Platform.itemComparisons().isSameItem(this.correctOutput, testOutput)) {
                this.testFrame.setInventorySlotContents(slotIndex, this.crafting.getStackInSlot(slotIndex));
                this.markItemAs(slotIndex, i, TestStatus.ACCEPT);
                return true;
            }
        }

        this.markItemAs(slotIndex, i, TestStatus.DECLINE);
        return false;
    }

    @Override
    public boolean isCraftable() {
        return this.isCrafting;
    }

    @Override
    public IAEItemStack[] getInputs() {
        return this.inputs;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return this.condensedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return this.condensedOutputs;
    }

    @Override
    public IAEItemStack[] getOutputs() {
        return this.outputs;
    }

    @Override
    public boolean canSubstitute() {
        return this.canSubstitute;
    }

    @Override
    public ItemStack getOutput(final CraftingInventory craftingInv, final World w) {
        if (!this.isCrafting) {
            throw new IllegalStateException("Only crafting recipes supported.");
        }

        for (int x = 0; x < craftingInv.getSizeInventory(); x++) {
            if (!this.isValidItemForSlot(x, craftingInv.getStackInSlot(x), w)) {
                return ItemStack.EMPTY;
            }
        }

        if (this.outputs != null && this.outputs.length > 0) {
            return this.outputs[0].createItemStack();
        }

        return ItemStack.EMPTY;
    }

    private TestStatus getStatus(final int slotIndex, final ItemStack i) {
        if (this.crafting.getStackInSlot(slotIndex).isEmpty()) {
            return i.isEmpty() ? TestStatus.ACCEPT : TestStatus.DECLINE;
        }

        if (i.isEmpty()) {
            return TestStatus.DECLINE;
        }

        if (i.hasTag()) {
            return TestStatus.TEST;
        }

        if (this.passCache.contains(new TestLookup(slotIndex, i))) {
            return TestStatus.ACCEPT;
        }

        if (this.failCache.contains(new TestLookup(slotIndex, i))) {
            return TestStatus.DECLINE;
        }

        return TestStatus.TEST;
    }

    @Override
    public int getPriority() {
        return this.priority;
    }

    @Override
    public void setPriority(final int priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(final PatternHelper o) {
        return Integer.compare(o.priority, this.priority);
    }

    @Override
    public int hashCode() {
        return this.pattern.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }

        final PatternHelper other = (PatternHelper) obj;

        if (this.pattern != null && other.pattern != null) {
            return this.pattern.equals(other.pattern);
        }
        return false;
    }

    private enum TestStatus {
        ACCEPT, DECLINE, TEST
    }

    private static final class TestLookup {

        private final int slot;
        private final int ref;
        private final int hash;

        public TestLookup(final int slot, final ItemStack i) {
            this(slot, i.getItem(), i.getDamage());
        }

        public TestLookup(final int slot, final Item item, final int dmg) {
            this.slot = slot;
            this.ref = (dmg << Platform.DEF_OFFSET) | (Item.getIdFromItem(item) & 0xffff);
            final int offset = 3 * slot;
            this.hash = (this.ref << offset) | (this.ref >> (offset + 32));
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(final Object obj) {
            final boolean equality;

            if (obj instanceof TestLookup) {
                final TestLookup b = (TestLookup) obj;

                equality = b.slot == this.slot && b.ref == this.ref;
            } else {
                equality = false;
            }

            return equality;
        }
    }
}
