package net.gegy1000.tictacs.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class TicTacsMixinPlugin implements IMixinConfigPlugin {
    private static final String MIXIN_ROOT = "net.gegy1000.tictacs.mixin";
    private static final String STARLIGHT_MIXIN_ROOT = MIXIN_ROOT + ".starlight";

    private static final boolean STARLIGHT_LOADED = FabricLoader.getInstance().isModLoaded("starlight");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(MIXIN_ROOT)) {
            return STARLIGHT_LOADED || !mixinClassName.startsWith(STARLIGHT_MIXIN_ROOT);
        } else {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
