package com.oddlabs.converter;

import com.oddlabs.geometry.AnimationInfo;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

public final class ConvertToBinary {
    private static final String ATTACHMENTS_FILE = "attachments.txt";
    private static final String SKINS_FILE = "skins.txt";
    private static final String DECORATIONS_FILE = "decorations.txt";
    private static final String LIGHTS_FILE = "lights.txt";
    private static final Set<String> DECORATION_GROUNDS = Set.of("beach", "dirt", "grass", "snow", "land");
    private static final int DEFAULT_DECORATION_COUNT = 20;
    private static final int MAX_DECORATION_COUNT = 1000;
    private static final String NO_EVENT = "-";

    // Every attribute geometry.xml declares on a sprite, read and checked once; the node is kept for the model,
    // skeleton and animation children. Checks against other sprites of the group happen where the group is known.
    private record Sprite(@NonNull Node node, @NonNull String name, float scale, @Nullable String base,
                          @Nullable String slot, boolean defaultOn, @NonNull String event, @Nullable String skin,
                          @Nullable String replaces, @Nullable String decoration, int count) {

        static @NonNull Sprite read(@NonNull Node n) {
            String name = getName(n);
            String scale = attribute(n, "scale");
            String event = attribute(n, "event");
            String base = attribute(n, "base");
            String slot = attribute(n, "slot");
            String skin = attribute(n, "skin");
            String decoration = attribute(n, "decoration");
            String count = attribute(n, "count");
            if (slot != null && base == null)
                throw new RuntimeException("Sprite " + name + " has a slot but no base");
            if (decoration == null && count != null)
                throw new RuntimeException("Sprite " + name + " has a count but no decoration");
            if (decoration != null) {
                if (!DECORATION_GROUNDS.containsAll(List.of(decoration.split(",", -1))))
                    throw new RuntimeException(
                            "Sprite " + name + " has decoration=\"" + decoration + "\"; use one or more of " + String.join(
                                    ", ",
                                    DECORATION_GROUNDS.stream().sorted().toList()) + ", comma separated");
                if (slot != null || skin != null)
                    throw new RuntimeException("Sprite " + name + " cannot be a decoration and an attachment or skin");
            }
            if (count == null)
                count = Integer.toString(DEFAULT_DECORATION_COUNT);
            if (!count.matches("[0-9]{1,4}") || Integer.parseInt(count) < 1 || Integer.parseInt(
                    count) > MAX_DECORATION_COUNT)
                throw new RuntimeException(
                        "Sprite " + name + " needs a count from 1 to " + MAX_DECORATION_COUNT + ", not " + count);
            return new Sprite(n, name, scale != null ? Float.parseFloat(scale) : 1f, base, slot,
                    Boolean.parseBoolean(attribute(n, "default")), event != null ? event : NO_EVENT, skin,
                    attribute(n, "replaces"), decoration, Integer.parseInt(count));
        }
    }

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
            List<String> attachments = new ArrayList<>();
            List<String> skins = new ArrayList<>();
            List<String> decorations = new ArrayList<>();
            List<String> lights = new ArrayList<>();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i).getNodeType() == Node.ELEMENT_NODE)
                    parseGroup(nl.item(i), registry, src_dir, build_dir, attachments, skins, decorations, lights);
            }
            Collections.sort(attachments);
            Collections.sort(skins);
            Collections.sort(decorations);
            Collections.sort(lights);
            try {
                Files.createDirectories(build_dir);
                Files.write(build_dir.resolve(ATTACHMENTS_FILE), attachments);
                Files.write(build_dir.resolve(SKINS_FILE), skins);
                Files.write(build_dir.resolve(DECORATIONS_FILE), decorations);
                Files.write(build_dir.resolve(LIGHTS_FILE), lights);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void parseGroup(@NonNull Node n, @NonNull Path registry, @NonNull Path src_dir,
            @NonNull Path build_dir,
            @NonNull List<String> attachments, @NonNull List<String> skins, @NonNull List<String> decorations,
            @NonNull List<String> lights) {
        if (n.hasChildNodes()) {
            String group = getName(n);
            Path new_build_dir = build_dir.resolve(group);
            NodeList nl = n.getChildNodes();
            Map<String, Sprite> sprites = new HashMap<>();
            for (int i = 0; i < nl.getLength(); i++) {
                Node child = nl.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals("sprite")) {
                    Sprite sprite = Sprite.read(child);
                    sprites.put(sprite.name(), sprite);
                }
            }
            for (Sprite sprite : sprites.values()) {
                ModelObjectInfo[] models = getModelObjectInfos(sprite, src_dir);
                parseSprite(sprite, models, sprites, registry, src_dir, new_build_dir);
                String base = sprite.base();
                String slot = sprite.slot();
                String skin = sprite.skin();
                String decoration = sprite.decoration();
                // An item skin only stands in for its item, so it is never offered as an item of its own.
                if (slot != null && base != null && skin == null)
                    attachments.add(attachmentLine(group, sprite, base, slot, models));
                if (skin != null)
                    skins.add(skinLine(group, sprite, skin, models, sprites));
                if (decoration != null)
                    decorations.add(decorationLine(group, sprite, decoration));
                lights.addAll(lightLines(group, sprite, models, src_dir));
            }
        }
    }

    // group base slot order name textures event: one line per sprite with a slot, default-on sprites sorted first in
    // their slot.
    private static @NonNull String attachmentLine(@NonNull String group, @NonNull Sprite sprite, @NonNull String base,
            @NonNull String slot, ModelObjectInfo @NonNull [] models) {
        int order = sprite.defaultOn() ? 0 : 1;
        return String.join(" ", group, base, slot, Integer.toString(order), sprite.name(),
                Integer.toString(models[0].getTextures().length), sprite.event());
    }

    // group skin replaces name textures event: one line per sprite that stands in for another sprite of its group in a
    // skin.
    private static @NonNull String skinLine(@NonNull String group, @NonNull Sprite sprite, @NonNull String skin,
            ModelObjectInfo @NonNull [] models, @NonNull Map<String, Sprite> group_sprites) {
        String replaces = sprite.replaces();
        if (replaces == null || !group_sprites.containsKey(replaces))
            throw new RuntimeException(
                    "Sprite " + sprite.name() + " is in skin " + skin + " but replaces no sprite of group " + group);
        Sprite replaced = group_sprites.get(replaces);
        if (!Objects.equals(sprite.slot(), replaced.slot()) || (sprite.slot() != null && !Objects.equals(
                sprite.base(), replaced.base())))
            throw new RuntimeException(
                    "Sprite " + sprite.name() + " needs the slot and base of " + replaces + ", the item it replaces");
        return String.join(" ", group, skin, replaces, sprite.name(), Integer.toString(
                models[0].getTextures().length), sprite.event());
    }

    // group name texture color radius strength x y z: one line per spot where the glow of a texture of the sprite lights
    // its surroundings at night.
    private static @NonNull List<String> lightLines(@NonNull String group, @NonNull Sprite sprite,
            ModelObjectInfo @NonNull [] models, @NonNull Path src_dir) {
        List<String> lines = new ArrayList<>();
        if (models.length == 0)
            return lines;
        TextureInfo[] textures = models[0].getTextures();
        for (int i = 0; i < textures.length; i++) {
            TextureInfo.Light light = textures[i].light();
            if (light == null)
                continue;
            Path glow = src_dir.toAbsolutePath().resolveSibling("textures").resolve("models").resolve(
                    Objects.requireNonNull(textures[i].emissive()) + ".png");
            try {
                for (float[] spot : LightSpots.find(models[0].getFile(), glow, sprite.scale())) {
                    lines.add(String.join(" ", group, sprite.name(), Integer.toString(i), light.color(),
                            light.reach(), light.strength(),
                            String.format(Locale.ROOT, "%.2f %.2f %.2f", spot[0], spot[1], spot[2])));
                }
            } catch (Exception e) {
                throw new RuntimeException("Sprite " + sprite.name() + " has a light but its glow cannot be read", e);
            }
        }
        return lines;
    }

    private static @Nullable String attribute(@NonNull Node n, @NonNull String name) {
        Node attribute = n.getAttributes().getNamedItem(name);
        return attribute != null ? attribute.getNodeValue() : null;
    }

    // group name ground count event: one line per sprite the game scatters over the map as scenery.
    private static @NonNull String decorationLine(@NonNull String group, @NonNull Sprite sprite,
            @NonNull String ground) {
        return String.join(" ", group, sprite.name(), ground, Integer.toString(sprite.count()), sprite.event());
    }

    private static boolean isModified(@NonNull Path src, @NonNull Path dest) {
        try {
            return !Files.exists(dest) || Files.getLastModifiedTime(dest).compareTo(Files.getLastModifiedTime(
                    src)) <= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static ModelObjectInfo @NonNull [] getModelObjectInfos(@NonNull Sprite sprite, @NonNull Path src_dir) {
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

    private static void parseSprite(@NonNull Sprite sprite, ModelObjectInfo @NonNull [] model_object_infos,
            @NonNull Map<String, Sprite> group_sprites, @NonNull Path registry, @NonNull Path src_dir,
            @NonNull Path build_dir) {
        String name = sprite.name();
        // base="peon" makes an attachment share the unit's skeleton and clip list without repeating them.
        Sprite base = sprite;
        for (int depth = 0; base.base() != null; depth++) {
            String base_name = base.base();
            base = group_sprites.get(base_name);
            if (base == null || depth > group_sprites.size())
                throw new RuntimeException("Sprite " + name + " has an unknown or circular base " + base_name);
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
