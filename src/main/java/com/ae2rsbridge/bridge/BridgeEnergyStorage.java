package com.ae2rsbridge.bridge;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IAEPowerStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

public class BridgeEnergyStorage implements IAEPowerStorage, IEnergyStorage {

    // Default values (used when config is not available)
    private static final int DEFAULT_MAX_FE = 2000;
    private static final int DEFAULT_FE_RATE = 2000;
    private static final double DEFAULT_AE_FE_RATIO = 2.0;

    private static int getMaxFE() {
        try {
            double aeCapacity = com.ae2rsbridge.config.BridgeConfig.ENERGY_BUFFER_CAPACITY.get();
            return (int) Math.floor(aeCapacity * getRatio());
        } catch (Exception e) {
            return DEFAULT_MAX_FE;
        }
    }

    private static double getRatio() {
        try {
            return com.ae2rsbridge.config.BridgeConfig.ENERGY_CONVERSION_RATIO.get();
        } catch (Exception e) {
            return DEFAULT_AE_FE_RATIO;
        }
    }

    private static int getMaxRate() {
        try {
            double aeRate = com.ae2rsbridge.config.BridgeConfig.MAX_TRANSFER_PER_TICK.get();
            return (int) Math.floor(aeRate * getRatio());
        } catch (Exception e) {
            return DEFAULT_FE_RATE;
        }
    }

    private int feEnergy = 0;
    private int lastTickExtract = 0;
    private boolean activeOutput = false;

    public void setFEEnergy(int energy) {
        this.feEnergy = Math.max(0, Math.min(energy, getMaxFE()));
    }

    public int getFECurrentPower() {
        return feEnergy;
    }

    @Override
    public double injectAEPower(double amt, Actionable mode) {
        if (amt <= 0) return 0;
        int feToAdd = (int) (amt * getRatio());
        int space = getMaxFE() - feEnergy;
        int actualFe = Math.min(feToAdd, space);
        if (mode == Actionable.MODULATE) {
            feEnergy += actualFe;
        }
        double actualAe = actualFe / getRatio();
        return amt - actualAe;
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
        return 0;
    }

    @Override
    public double getAEMaxPower() {
        return getMaxFE() / getRatio();
    }

    @Override
    public double getAECurrentPower() {
        return feEnergy / getRatio();
    }

    @Override
    public boolean isAEPublicPowerStorage() {
        return true;
    }

    @Override
    public AccessRestriction getPowerFlow() {
        return AccessRestriction.WRITE;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        return 0;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (maxExtract <= 0) return 0;
        int availableThisTick = getMaxRate() - lastTickExtract;
        int canExtract = Math.min(availableThisTick, feEnergy);
        int actual = Math.min(canExtract, maxExtract);
        if (!simulate) {
            feEnergy -= actual;
            lastTickExtract += actual;
        }
        return actual;
    }

    public void resetTickExtract() {
        lastTickExtract = 0;
    }

    public boolean isActiveOutput() {
        return activeOutput;
    }

    public void setActiveOutput(boolean active) {
        this.activeOutput = active;
    }

    @Override
    public int getEnergyStored() {
        return feEnergy;
    }

    @Override
    public int getMaxEnergyStored() {
        return getMaxFE();
    }

    @Override
    public boolean canReceive() {
        return false;
    }

    @Override
    public boolean canExtract() {
        return feEnergy > 0;
    }
}
