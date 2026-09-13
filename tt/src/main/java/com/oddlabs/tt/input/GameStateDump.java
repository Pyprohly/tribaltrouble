package com.oddlabs.tt.input;

import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.delegate.InGameDelegate;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.viewer.WorldViewer;
import org.jspecify.annotations.NonNull;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * Text dump of the running game for scripted input: local player, camera, and every unit and building with world and
 * screen coordinates (framebuffer pixels, top-left origin, matching screenshots and the GUI tree).
 */
public final class GameStateDump {
    private GameStateDump() {
    }

    public static @NonNull String dump(@NonNull GUIRoot root, int framebuffer_width, int framebuffer_height) {
        if (!(root.getDelegate() instanceof InGameDelegate delegate)) {
            return "err not in game (delegate " + root.getDelegate().getClass().getSimpleName() + ")\n";
        }
        WorldViewer viewer = delegate.getViewer();
        Player local = viewer.getLocalPlayer();
        Player[] players = viewer.getWorld().getPlayers();
        CameraState camera = viewer.getCamera().getState();
        int[] viewport = {0, 0, framebuffer_width, framebuffer_height};
        Vector3f screen = new Vector3f();
        StringBuilder out = new StringBuilder("ok\n");
        out.append("delegate ").append(delegate.getClass().getSimpleName()).append('\n');
        out.append(String.format(Locale.ROOT, "camera target %.1f %.1f %.1f%n", camera.getTargetX(),
                camera.getTargetY(), camera.getTargetZ()));
        for (int i = 0; i < players.length; i++) {
            Player p = players[i];
            out.append("player ").append(i).append(' ').append(p == local ? "local " : "").append(
                    p.getPlayerInfo().getName()).append(" team ").append(p.getPlayerInfo().getTeam()).append(
                            " units ").append(p.getUnitCountContainer().getNumSupplies()).append('/').append(
                                    viewer.getWorld().getMaxUnitCount()).append(" buildings ").append(
                                            p.getBuildingCountContainer().getNumSupplies()).append('\n');
        }
        var selection = viewer.getSelection().getCurrentSelection();
        for (int i = 0; i < players.length; i++) {
            for (Selectable<?> s : players[i].getUnits().getSet()) {
                if (s.isDead()) {
                    continue;
                }
                camera.getProjectionModelView().project(s.getPositionX(), s.getPositionY(), s.getPositionZ(),
                        viewport, screen);
                String kind = s instanceof Building ? "building" : s instanceof Unit ? "unit" : "object";
                int hp = s instanceof Unit u ? u.getHitPoints() : s instanceof Building b ? b.getHitPoints() : -1;
                out.append(kind).append(' ').append(i).append(' ').append(s.getTemplate().getName()).append(
                        String.format(Locale.ROOT, " world %.1f %.1f %.1f screen %d %d hp %d", s.getPositionX(),
                                s.getPositionY(), s.getPositionZ(), Math.round(screen.x),
                                Math.round(framebuffer_height - screen.y), hp)).append(
                                        selection.contains(s) ? " selected" : "").append('\n');
            }
        }
        return out.toString();
    }
}
