package org.hyzionstudios.mysticquests.service;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The picture the dialogue's character card shows for the NPC who is speaking.
 *
 * <p>Hytale 0.7 cannot draw a live entity inside a server-driven custom page.
 * {@code CharacterPreviewComponent} and {@code PlayerPreviewComponent} preview the local player and
 * expose no model to set, and {@code UICommandBuilder} can only send {@code Area},
 * {@code ItemGridSlot}, {@code ItemStack}, {@code LocalizableString}, {@code PatchStyle},
 * {@code DropdownEntryInfo} and {@code Anchor} values. Vanilla shows an NPC in a custom page the way
 * the Memories page does: an {@code AssetImage} whose {@code AssetPath} is the model's generated
 * portrait, {@code Icons/ModelsGenerated/<id>.png} ({@code NPCMemory#getIconPath},
 * {@code MemoriesPage#updateMemoryDetailsPanel}). This class picks that portrait.
 *
 * <p>Candidates, first that exists wins: the conversation's authored {@code portrait} (a Common
 * asset path, or a model id), the entity's model {@code Icon}, the generated portrait named after
 * its model, then the one named after its NPC role. Only a path the server has registered as a
 * Common asset is ever sent, so a missing portrait falls back to the speaker's initials instead of a
 * broken image.
 */
public final class SpeakerPortraits {
    static final String MODEL_ICON_ROOT = "Icons/ModelsGenerated/";

    private SpeakerPortraits() {
    }

    /** The portrait for the entity at {@code ref}, or null when none exists. */
    public static String forEntity(String authored, ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        String modelAssetId = null;
        String roleName = null;
        if (accessor != null && ref != null && ref.isValid()) {
            modelAssetId = modelAssetId(accessor, ref);
            roleName = roleName(accessor, ref);
        }
        return resolve(authored, modelAssetId, roleName);
    }

    /** The portrait for an NPC known only by its model and role ids, or null when none exists. */
    public static String resolve(String authored, String modelAssetId, String roleName) {
        return choose(candidates(authored, modelAssetId, roleName, SpeakerPortraits::modelIcon),
                SpeakerPortraits::exists);
    }

    /** Every portrait worth trying, most specific first, without duplicates or blanks. */
    static List<String> candidates(
            String authored, String modelAssetId, String roleName, Function<String, String> modelIcon) {
        List<String> candidates = new ArrayList<>();
        String portrait = blankToNull(authored);
        if (portrait != null) {
            if (portrait.toLowerCase(Locale.ROOT).endsWith(".png")) {
                add(candidates, normalisePath(portrait));
            } else {
                add(candidates, modelIcon.apply(portrait));
                add(candidates, MODEL_ICON_ROOT + portrait + ".png");
            }
        }
        String model = blankToNull(modelAssetId);
        if (model != null) {
            add(candidates, modelIcon.apply(model));
            add(candidates, MODEL_ICON_ROOT + model + ".png");
        }
        String role = blankToNull(roleName);
        if (role != null) {
            add(candidates, MODEL_ICON_ROOT + role + ".png");
        }
        return candidates;
    }

    /** The first candidate that exists, or null. */
    static String choose(List<String> candidates, Predicate<String> exists) {
        for (String candidate : candidates) {
            if (exists.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A Common asset path as the registry names it: forward slashes, no {@code Common/} root. */
    static String normalisePath(String path) {
        String normalised = path.trim().replace('\\', '/');
        while (normalised.startsWith("/")) {
            normalised = normalised.substring(1);
        }
        if (normalised.regionMatches(true, 0, "Common/", 0, "Common/".length())) {
            normalised = normalised.substring("Common/".length());
        }
        return normalised;
    }

    private static void add(List<String> candidates, String candidate) {
        String value = blankToNull(candidate);
        if (value != null && !candidates.contains(value)) {
            candidates.add(value);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** The {@code Icon} a model asset declares, e.g. {@code Icons/ModelsGenerated/Kweebec_Sapling.png}. */
    private static String modelIcon(String modelAssetId) {
        try {
            ModelAsset asset = ModelAsset.getAssetMap().getAsset(modelAssetId);
            return asset == null ? null : asset.getIcon();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Mirrors {@code CommonAssetValidator}: a UI texture may ship only as its {@code @2x} variant. */
    private static boolean exists(String path) {
        try {
            if (CommonAssetRegistry.hasCommonAsset(path)) {
                return true;
            }
            return path.endsWith(".png")
                    && CommonAssetRegistry.hasCommonAsset(path.substring(0, path.length() - ".png".length()) + "@2x.png");
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static String modelAssetId(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        try {
            ModelComponent model = accessor.getComponent(ref, ModelComponent.getComponentType());
            return model == null || model.getModel() == null ? null : model.getModel().getModelAssetId();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static String roleName(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        try {
            ComponentType<EntityStore, NPCEntity> type = NPCEntity.getComponentType();
            NPCEntity npc = type == null ? null : accessor.getComponent(ref, type);
            return npc == null ? null : npc.getRoleName();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }
}
