package com.oddlabs.converter;

import com.oddlabs.geometry.AnimationInfo;
import com.oddlabs.geometry.AttachmentEntry;
import com.oddlabs.geometry.DecorationEntry;
import com.oddlabs.geometry.LightEntry;
import com.oddlabs.geometry.Registry;
import com.oddlabs.geometry.SkinEntry;
import com.oddlabs.geometry.SpriteInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

public final class ConvertToBinary {

    void main(@NonNull String @NonNull... args) {
        if (args.length != 3)
            throw new IllegalArgumentException("Invalid number of arguments : <xml_file> <src_dir> <build_dir>");
        Path xml_file = Path.of(args[0]);
        Path src_dir = Path.of(args[1]);
        Path build_dir = Path.of(args[2]);

        try (var input_stream = Files.newInputStream(src_dir.resolve(xml_file))) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setValidating(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new GeometryErrorHandler());
            Document document = builder.parse(input_stream);
            org.w3c.dom.Element root = document.getDocumentElement();
            parseGeometry(root, src_dir.resolve(xml_file), src_dir, build_dir);
        } catch (Exception e) {
            System.err.println("Error processing " + xml_file);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void parseGeometry(@NonNull Node n, @NonNull Path registry, @NonNull Path src_dir,
            @NonNull Path build_dir) {
        if (n.hasChildNodes()) {
            NodeList nl = n.getChildNodes();
            List<AttachmentEntry> attachments = new ArrayList<>();
            List<SkinEntry> skins = new ArrayList<>();
            List<DecorationEntry> decorations = new ArrayList<>();
            List<LightEntry> lights = new ArrayList<>();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i).getNodeType() == Node.ELEMENT_NODE)
                    parseGroup(nl.item(i), registry, src_dir, build_dir, attachments, skins, decorations, lights);
            }
            attachments.sort(AttachmentEntry.ORDER);
            skins.sort(SkinEntry.ORDER);
            decorations.sort(DecorationEntry.ORDER);
            lights.sort(LightEntry.ORDER);
            try {
                Files.createDirectories(build_dir);
                new Registry(attachments, skins, decorations, lights).write(build_dir.resolve(Registry.FILE));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void parseGroup(@NonNull Node n, @NonNull Path registry, @NonNull Path src_dir,
            @NonNull Path build_dir,
            @NonNull List<AttachmentEntry> attachments, @NonNull List<SkinEntry> skins,
            @NonNull List<DecorationEntry> decorations, @NonNull List<LightEntry> lights) {
        if (n.hasChildNodes()) {
            String group = getName(n);
            Path new_build_dir = build_dir.resolve(group);
            NodeList nl = n.getChildNodes();
            Map<String, SpriteEntry> sprites = new HashMap<>();
            for (int i = 0; i < nl.getLength(); i++) {
                Node child = nl.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals("sprite")) {
                    SpriteEntry sprite = SpriteEntry.read(child);
                    sprites.put(sprite.name(), sprite);
                }
            }
            for (SpriteEntry sprite : sprites.values()) {
                ModelObjectInfo[] models = getModelObjectInfos(sprite, src_dir);
                parseSprite(sprite, models, sprites, registry, src_dir, new_build_dir);
                String base = sprite.base();
                String slot = sprite.slot();
                String skin = sprite.skin();
                String decoration = sprite.decoration();
                // An item skin only stands in for its item, so it is never offered as an item of its own.
                if (slot != null && base != null && skin == null)
                    attachments.add(attachment(group, sprite, base, slot, models));
                if (skin != null)
                    skins.add(skin(group, sprite, skin, models, sprites));
                if (decoration != null)
                    decorations.add(decoration(group, sprite, decoration));
                lights.addAll(lights(group, sprite, models, src_dir));
            }
        }
    }

    private static @NonNull AttachmentEntry attachment(@NonNull String group, @NonNull SpriteEntry sprite,
            @NonNull String base, @NonNull String slot, ModelObjectInfo @NonNull [] models) {
        return new AttachmentEntry(group, base, slot, sprite.defaultOn(), sprite.name(),
                models[0].getTextures().length, sprite.event());
    }

    private static @NonNull SkinEntry skin(@NonNull String group, @NonNull SpriteEntry sprite, @NonNull String skin,
            ModelObjectInfo @NonNull [] models, @NonNull Map<String, SpriteEntry> group_sprites) {
        String replaces = sprite.replaces();
        if (replaces == null || !group_sprites.containsKey(replaces))
            throw new RuntimeException(
                    "Sprite " + sprite.name() + " is in skin " + skin + " but replaces no sprite of group " + group);
        SpriteEntry replaced = group_sprites.get(replaces);
        if (!Objects.equals(sprite.slot(), replaced.slot()) || (sprite.slot() != null && !Objects.equals(
                sprite.base(), replaced.base())))
            throw new RuntimeException(
                    "Sprite " + sprite.name() + " needs the slot and base of " + replaces + ", the item it replaces");
        return new SkinEntry(group, skin, replaces, sprite.name(), models[0].getTextures().length, sprite.event());
    }

    // One entry per spot where the glow of a texture of the sprite lights its surroundings at night.
    private static @NonNull List<LightEntry> lights(@NonNull String group, @NonNull SpriteEntry sprite,
            ModelObjectInfo @NonNull [] models, @NonNull Path src_dir) {
        List<LightEntry> entries = new ArrayList<>();
        if (models.length == 0)
            return entries;
        TextureInfo[] textures = models[0].getTextures();
        for (int i = 0; i < textures.length; i++) {
            TextureInfo.Light light = textures[i].light();
            if (light == null)
                continue;
            Path glow = src_dir.toAbsolutePath().resolveSibling("textures").resolve("models").resolve(
                    Objects.requireNonNull(textures[i].emissive()) + ".png");
            try {
                for (float[] spot : LightSpots.find(models[0].getFile(), glow, sprite.scale())) {
                    entries.add(new LightEntry(group, sprite.name(), i, light.color(), Float.parseFloat(light.reach()),
                            Float.parseFloat(light.strength()), spot[0], spot[1], spot[2]));
                }
            } catch (Exception e) {
                throw new RuntimeException("Sprite " + sprite.name() + " has a light but its glow cannot be read", e);
            }
        }
        return entries;
    }

    private static @NonNull DecorationEntry decoration(@NonNull String group, @NonNull SpriteEntry sprite,
            @NonNull String ground) {
        return new DecorationEntry(group, sprite.name(), ground, sprite.count(), sprite.event());
    }

    private static boolean isModified(@NonNull Path src, @NonNull Path dest) {
        try {
            return !Files.exists(dest) || Files.getLastModifiedTime(dest).compareTo(Files.getLastModifiedTime(
                    src)) <= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static ModelObjectInfo @NonNull [] getModelObjectInfos(@NonNull SpriteEntry sprite, @NonNull Path src_dir) {
        NodeList nl = sprite.node().getChildNodes();
        List<ModelObjectInfo> object_infos = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            Node item = nl.item(i);
            if (item.getNodeName().equals("model")) {
                float r = getInt(item, "r") / 255f;
                float g = getInt(item, "g") / 255f;
                float b = getInt(item, "b") / 255f;
                TextureInfo[] textures = getTextureInfos(item, sprite.name());
                object_infos.add(new ModelObjectInfo(src_dir.resolve(getText(item)), textures, new float[]{r, g, b}));
            }
        }
        ModelObjectInfo[] infos = new ModelObjectInfo[object_infos.size()];
        return object_infos.toArray(infos);
    }

    private static @NonNull AnimObjectInfo @NonNull [] getAnimObjectInfos(@NonNull Node n, @NonNull Path src_dir) {
        NodeList nl = n.getChildNodes();
        List<AnimObjectInfo> object_infos = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            Node item = nl.item(i);
            if (item.getNodeName().equals("animation")) {
                float wpc = Float.parseFloat(item.getAttributes().getNamedItem("wpc").getNodeValue());
                assert wpc != 0f;
                String type_str = item.getAttributes().getNamedItem("type").getNodeValue();
                AnimationInfo.AnimationType type = getTypeFromString(type_str);
                String animName = item.getAttributes().getNamedItem("name").getNodeValue();
                object_infos.add(new AnimObjectInfo(src_dir.resolve(getText(item)), wpc, type, animName));
            }
        }
        AnimObjectInfo[] infos = new AnimObjectInfo[object_infos.size()];
        return object_infos.toArray(infos);
    }

    private static @NonNull TextureInfo @NonNull [] getTextureInfos(@NonNull Node n, @NonNull String sprite) {
        NodeList nl = n.getChildNodes();
        List<TextureInfo> textures = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            Node item = nl.item(i);
            if (item.getNodeName().equals("texture"))
                textures.add(TextureInfo.read(item, sprite));
        }
        return textures.toArray(new TextureInfo[0]);
    }

    private static void parseSprite(@NonNull SpriteEntry sprite, ModelObjectInfo @NonNull [] model_object_infos,
            @NonNull Map<String, SpriteEntry> group_sprites, @NonNull Path registry, @NonNull Path src_dir,
            @NonNull Path build_dir) {
        String name = sprite.name();
        // base="peon" makes an attachment share the unit's skeleton and clip list without repeating them.
        SpriteEntry base = sprite;
        for (int depth = 0; base.base() != null; depth++) {
            String base_name = base.base();
            base = group_sprites.get(base_name);
            if (base == null || depth > group_sprites.size())
                throw new RuntimeException("SpriteEntry " + name + " has an unknown or circular base " + base_name);
        }
        ObjectInfo skeleton_info = getSkeletonObjectInfo(sprite.node(), src_dir);
        AnimObjectInfo[] anim_object_infos = getAnimObjectInfos(sprite.node(), src_dir);
        // A sprite that brings nothing of its own refers to its base's clips instead of carrying a copy of them.
        boolean shares_clips = !base.name().equals(name) && skeleton_info == null && anim_object_infos.length == 0;
        if (skeleton_info == null)
            skeleton_info = getSkeletonObjectInfo(base.node(), src_dir);
        if (anim_object_infos.length == 0)
            anim_object_infos = getAnimObjectInfos(base.node(), src_dir);
        Path build_file = build_dir.resolve(name + ".binsprite");

        // Texture lists, scale and clip settings live in the registry, not in the mesh files.
        boolean modified = isModified(registry, build_file)
                || (skeleton_info != null && isModified(skeleton_info.getFile(), build_file));
        for (AnimObjectInfo anim_object_info : anim_object_infos) {
            if (isModified(anim_object_info.getFile(), build_file)) {
                modified = true;
                break;
            }
        }
        for (ModelObjectInfo model_object_info : model_object_infos) {
            if (isModified(model_object_info.getFile(), build_file)) {
                modified = true;
                break;
            }
        }
        if (modified) {
            AnimationInfo[] animations;
            Map<String, Bone> name_to_bone_map;
            if (skeleton_info != null) {
                Skeleton skeleton = SkeletonLoader.loadSkeleton(skeleton_info.getFile());
                name_to_bone_map = skeleton.getNameToBoneMap();
                animations = new AnimationInfo[anim_object_infos.length];
                for (int i = 0; i < anim_object_infos.length; i++) {
                    AnimObjectInfo current = anim_object_infos[i];
                    Map<String, float[]>[] animation_map = AnimationLoader.loadAnimation(current.getFile());
                    assert animations[i] == null;
                    animations[i] = Optimizer.convertToAnimation(skeleton.getBoneRoot(), skeleton.getInitialPose(),
                            animation_map, current.getType(), current.getWPC(), current.getName());
                }
            } else {
                float[][] identity_frame = {{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}};
                animations = new AnimationInfo[]{new AnimationInfo(identity_frame, AnimationInfo.AnimationType.LOOP, 1f,
                        "identity")};
                name_to_bone_map = null;
            }
            SpriteInfo[] sprite_models = new SpriteInfo[model_object_infos.length];
            for (int i = 0; i < model_object_infos.length; i++) {
                ModelObjectInfo current = model_object_infos[i];
                ModelInfo model_info = MeshLoader.loadMesh(current.getFile(), name_to_bone_map, sprite.scale());
                assert sprite_models[i] == null;
                String[][] textures = Arrays.stream(current.getTextures()).map(TextureInfo::names).toArray(
                        String[][]::new);
                sprite_models[i] = Optimizer.convertToSprite(textures, model_info, current.getClearColor());
            }
            Object clips = shares_clips ? build_dir.getFileName() + "/" + base.name() : animations;
            write(new Object[]{sprite_models, clips}, build_file);
        }
    }

    private static @Nullable ObjectInfo getSkeletonObjectInfo(@NonNull Node n, @NonNull Path src_dir) {
        NodeList nl = n.getChildNodes();
        return IntStream.range(0, nl.getLength()).mapToObj(nl::item).filter(item -> item.getNodeName().equals(
                "skeleton")).findFirst().map(item -> new ObjectInfo(src_dir.resolve(getText(item)))).orElse(null);
    }

    public static Node getNodeByName(String name, @NonNull Node n) {
        NodeList nl = n.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeName().equals(name))
                return nl.item(i);
        }
        throw new RuntimeException("Missing node: " + name);
    }

    private static String getName(@NonNull Node n) {
        return n.getAttributes().getNamedItem("name").getNodeValue();
    }

    private static int getInt(@NonNull Node n, String key) {
        String string = n.getAttributes().getNamedItem(key).getNodeValue();
        return Integer.parseInt(string);
    }

    private static AnimationInfo.@NonNull AnimationType getTypeFromString(@NonNull String str) {
        return switch (str) {
            case "loop" -> AnimationInfo.AnimationType.LOOP;
            case "plain" -> AnimationInfo.AnimationType.PLAIN;
            default -> throw new RuntimeException("Unknown animation type: " + str);
        };
    }

    private static @NonNull String getText(@NonNull Node n) {
        return n.getFirstChild().getNodeValue().trim();
    }

    private static void write(Object output, @NonNull Path file) {
        System.err.println("Saving to " + file);

        try {
            Files.createDirectories(file.getParent());
            try (var obj_stream = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                obj_stream.writeObject(output);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}
