package shortestpath;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.Getter;
import net.runelite.api.Point;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.*;

@PluginDescriptor(
    name = "Shortest Path",
    description = "Draws the shortest path to a chosen destination on the map (right click a spot on the world map to use)",
    tags = {"pathfinder", "map", "waypoint", "navigation"}
)
public class ShortestPathPlugin extends Plugin {
    protected static final String CONFIG_GROUP = "shortestpath";
    private static final String ADD_START = "Add start";
    private static final String ADD_END = "Add end";
    private static final String CLEAR = "Clear";
    private static final String PATH = ColorUtil.wrapWithColorTag("Path", JagexColors.MENU_TARGET);
    private static final String SET = "Set";
    private static final String START = ColorUtil.wrapWithColorTag("Start", JagexColors.MENU_TARGET);
    private static final String TARGET = ColorUtil.wrapWithColorTag("Target", JagexColors.MENU_TARGET);
    private static final String TRANSPORT = ColorUtil.wrapWithColorTag("Transport", JagexColors.MENU_TARGET);
    private static final String WALK_HERE = "Walk here";
    private static final BufferedImage MARKER_IMAGE = ImageUtil.loadImageResource(ShortestPathPlugin.class, "/marker.png");

    @Inject
    private Client client;

    @Getter
    @Inject
    private ClientThread clientThread;

    @Inject
    private ShortestPathConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private PathTileOverlay pathOverlay;

    @Inject
    private PathMinimapOverlay pathMinimapOverlay;

    @Inject
    private PathMapOverlay pathMapOverlay;

    @Inject
    private PathMapTooltipOverlay pathMapTooltipOverlay;

    @Inject
    private SpriteManager spriteManager;

    @Inject
    private WorldMapPointManager worldMapPointManager;

    @Inject
    private WorldMapOverlay worldMapOverlay;

    private Point lastMenuOpenedPoint;
    private WorldMapPoint marker;
    private WorldPoint transportStart;
    private WorldPoint lastLocation = new WorldPoint(0, 0, 0);
    private MenuEntry lastClick;
    private Shape minimapClipFixed;
    private Shape minimapClipResizeable;
    private BufferedImage minimapSpriteFixed;
    private BufferedImage minimapSpriteResizeable;
    private Rectangle minimapRectangle = new Rectangle();

    @Getter
    private Pathfinder pathfinder;
    private PathfinderConfig pathfinderConfig;
    @Getter
    private boolean startPointSet = false;

    @Provides
    public ShortestPathConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ShortestPathConfig.class);
    }

    @Override
    protected void startUp() {
        CollisionMap map = CollisionMap.fromResources();
        Map<WorldPoint, List<Transport>> transports = Transport.fromResources(config);

        pathfinderConfig = new PathfinderConfig(map, transports, client, config, this);

        overlayManager.add(pathOverlay);
        overlayManager.add(pathMinimapOverlay);
        overlayManager.add(pathMapOverlay);
        overlayManager.add(pathMapTooltipOverlay);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(pathOverlay);
        overlayManager.remove(pathMinimapOverlay);
        overlayManager.remove(pathMapOverlay);
        overlayManager.remove(pathMapTooltipOverlay);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!CONFIG_GROUP.equals(event.getGroup())) {
            return;
        }

        boolean reloadTransports = "useAgilityShortcuts".equals(event.getKey()) ||
            "useGrappleShortcuts".equals(event.getKey()) || "useBoats".equals(event.getKey()) ||
            "useFairyRings".equals(event.getKey()) || "useTeleports".equals(event.getKey()) ||
            "useCanoes".equals(event.getKey()) || "useArdyCloak".equals(event.getKey());

        if (reloadTransports) {
            Map<WorldPoint, List<Transport>> transports = Transport.fromResources(config);
            pathfinderConfig.getTransports().clear();
            pathfinderConfig.getTransports().putAll(transports);
        }
    }

    public boolean isNearPath(WorldPoint location) {
        if (pathfinder == null || pathfinder.getPath() == null || pathfinder.getPath().isEmpty() ||
            config.recalculateDistance() < 0 || lastLocation.equals(lastLocation = location)) {
            return true;
        }

        for (WorldPoint point : pathfinder.getPath()) {
            if (location.distanceTo2D(point) < config.recalculateDistance()) {
                return true;
            }
        }

        return false;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        lastMenuOpenedPoint = client.getMouseCanvasPosition();
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || pathfinder == null) {
            return;
        }

        WorldPoint currentLocation = localPlayer.getWorldLocation();
        if (currentLocation.distanceTo(pathfinder.getTarget()) < config.reachedDistance()) {
            setTarget(null);
            return;
        }

        if (!startPointSet && !isNearPath(currentLocation)) {
            if (config.cancelInstead()) {
                setTarget(null);
                return;
            }
            pathfinder = new Pathfinder(pathfinderConfig, currentLocation, pathfinder.getTarget());
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (client.isKeyPressed(KeyCode.KC_SHIFT) && event.getOption().equals(WALK_HERE) && event.getTarget().isEmpty()) {
            if (config.drawTransports()) {
                addMenuEntry(event, ADD_START, TRANSPORT, 1);
                addMenuEntry(event, ADD_END, TRANSPORT, 1);
                // addMenuEntry(event, "Copy Position");
            }

            addMenuEntry(event, SET, TARGET, 1);
            if (pathfinder != null) {
                if (pathfinder.getTarget() != null) {
                    addMenuEntry(event, SET, START, 1);
                }
                WorldPoint selectedTile = getSelectedWorldPoint();
                if (pathfinder.getPath() != null) {
                    for (WorldPoint tile : pathfinder.getPath()) {
                        if (tile.equals(selectedTile)) {
                            addMenuEntry(event, CLEAR, PATH, 1);
                            break;
                        }
                    }
                }
            }
        }

        final Widget map = client.getWidget(WidgetInfo.WORLD_MAP_VIEW);

        if (map != null && map.getBounds().contains(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY())) {
            addMenuEntry(event, SET, TARGET, 0);
            if (pathfinder != null) {
                if (pathfinder.getTarget() != null) {
                    addMenuEntry(event, SET, START, 0);
                    addMenuEntry(event, CLEAR, PATH, 0);
                }
            }
        }

        final Shape minimap = getMinimapClipArea();

        if (minimap != null && pathfinder != null &&
            minimap.contains(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY())) {
            addMenuEntry(event, CLEAR, PATH, 0);
        }

        if (minimap != null && pathfinder != null &&
            ("Floating World Map".equals(Text.removeTags(event.getOption())) ||
             "Close Floating panel".equals(Text.removeTags(event.getOption())))) {
            addMenuEntry(event, CLEAR, PATH, 1);
        }
    }

    public Map<WorldPoint, List<Transport>> getTransports() {
        return pathfinderConfig.getTransports();
    }

    public CollisionMap getMap() {
        return pathfinderConfig.getMap();
    }

    private void onMenuOptionClicked(MenuEntry entry) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        WorldPoint currentLocation = localPlayer.getWorldLocation();
        if (entry.getOption().equals(ADD_START) && entry.getTarget().equals(TRANSPORT)) {
            transportStart = currentLocation;
        }

        if (entry.getOption().equals(ADD_END) && entry.getTarget().equals(TRANSPORT)) {
            WorldPoint transportEnd = client.getLocalPlayer().getWorldLocation();
            System.out.println(transportStart.getX() + " " + transportStart.getY() + " " + transportStart.getPlane() + " " +
                    currentLocation.getX() + " " + currentLocation.getY() + " " + currentLocation.getPlane() + " " +
                    lastClick.getOption() + " " + Text.removeTags(lastClick.getTarget()) + " " + lastClick.getIdentifier()
            );
            Transport transport = new Transport(transportStart, transportEnd);
            pathfinderConfig.getTransports().computeIfAbsent(transportStart, k -> new ArrayList<>()).add(transport);
        }

        if (entry.getOption().equals("Copy Position")) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection("(" + currentLocation.getX() + ", "
                            + currentLocation.getY() + ", "
                            + currentLocation.getPlane() + ")"), null);
        }

        if (entry.getOption().equals(SET) && entry.getTarget().equals(TARGET)) {
            setTarget(getSelectedWorldPoint());
        }

        if (entry.getOption().equals(SET) && entry.getTarget().equals(START)) {
            setStart(getSelectedWorldPoint());
        }

        if (entry.getOption().equals(CLEAR) && entry.getTarget().equals(PATH)) {
            setTarget(null);
        }

        if (entry.getType() != MenuAction.WALK) {
            lastClick = entry;
        }
    }

    private WorldPoint getSelectedWorldPoint() {
        if (client.getWidget(WidgetInfo.WORLD_MAP_VIEW) == null) {
            if (client.getSelectedSceneTile() != null) {
                return client.getSelectedSceneTile().getWorldLocation();
            }
        } else {
            return calculateMapPoint(client.isMenuOpen() ? lastMenuOpenedPoint : client.getMouseCanvasPosition());
        }
        return null;
    }

    public boolean playerHasAxe() {
        int[] axes = {1349,1351,1353,1355,1357,1359,1361,6739};
        return playerHasItem(axes);
    }

    public boolean playerHasArdyCloak() {
        int[] cloaks = {13121};
        return playerHasItem(cloaks);
    }

    private boolean playerHasItem(int[] items) {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        Item[] inventoryItems = inventory.getItems();
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        Item[] equipmentItems = equipment.getItems();
        for (Item item : equipmentItems)
        {
            int itemId = item.getId();
            for (int searchItemId : items) {
                if(itemId == searchItemId)
                    return true;
            }
        }
        for (Item item : inventoryItems)
        {
            int itemId = item.getId();
            for (int searchItemId : items) {
                if(itemId == searchItemId)
                    return true;
            }
        }
        return false;
    }

    public void setTarget(WorldPoint target) {
        Player localPlayer = client.getLocalPlayer();
        WorldPoint playerLocation = localPlayer.getWorldLocation();
        if (!startPointSet && localPlayer == null) {
            return;
        }
        pathfinderConfig.refresh();
        pathfinderConfig.getTemporaryTransports().clear();
        if (config.useArdyCloak()) {
            if (!config.checkArdyCloak() || playerHasArdyCloak())
            {
                Transport transport = new Transport(playerLocation, new WorldPoint(2607, 3221, 0), Transport.TransportType.ARDY_CLOAK);
                pathfinderConfig.getTemporaryTransports().computeIfAbsent(playerLocation, k -> new ArrayList<>()).add(transport);
            }
        }

        if (target == null) {
            worldMapPointManager.remove(marker);
            marker = null;
            pathfinder = null;
            startPointSet = false;
        } else {
            worldMapPointManager.removeIf(x -> x == marker);
            marker = new WorldMapPoint(target, MARKER_IMAGE);
            marker.setName("Target");
            marker.setTarget(marker.getWorldPoint());
            marker.setJumpOnClick(true);
            worldMapPointManager.add(marker);

            WorldPoint start = localPlayer.getWorldLocation();
            if (startPointSet && pathfinder != null) {
                start = pathfinder.getStart();
            }
            pathfinder = new Pathfinder(pathfinderConfig, start, target);
        }
    }

    private void setStart(WorldPoint start) {
        if (pathfinder == null) {
            return;
        }
        startPointSet = true;
        pathfinder = new Pathfinder(pathfinderConfig, start, pathfinder.getTarget());
    }

    public WorldPoint calculateMapPoint(Point point) {
        float zoom = client.getRenderOverview().getWorldMapZoom();
        RenderOverview renderOverview = client.getRenderOverview();
        final WorldPoint mapPoint = new WorldPoint(renderOverview.getWorldMapPosition().getX(), renderOverview.getWorldMapPosition().getY(), 0);
        final Point middle = mapWorldPointToGraphicsPoint(mapPoint);

        if (point == null || middle == null) {
            return null;
        }

        final int dx = (int) ((point.getX() - middle.getX()) / zoom);
        final int dy = (int) ((-(point.getY() - middle.getY())) / zoom);

        return mapPoint.dx(dx).dy(dy);
    }

    public Point mapWorldPointToGraphicsPoint(WorldPoint worldPoint) {
        RenderOverview ro = client.getRenderOverview();

        float pixelsPerTile = ro.getWorldMapZoom();

        Widget map = client.getWidget(WidgetInfo.WORLD_MAP_VIEW);
        if (map != null) {
            Rectangle worldMapRect = map.getBounds();

            int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
            int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);

            Point worldMapPosition = ro.getWorldMapPosition();

            int yTileMax = worldMapPosition.getY() - heightInTiles / 2;
            int yTileOffset = (yTileMax - worldPoint.getY() - 1) * -1;
            int xTileOffset = worldPoint.getX() + widthInTiles / 2 - worldMapPosition.getX();

            int xGraphDiff = ((int) (xTileOffset * pixelsPerTile));
            int yGraphDiff = (int) (yTileOffset * pixelsPerTile);

            yGraphDiff -= pixelsPerTile - Math.ceil(pixelsPerTile / 2);
            xGraphDiff += pixelsPerTile - Math.ceil(pixelsPerTile / 2);

            yGraphDiff = worldMapRect.height - yGraphDiff;
            yGraphDiff += (int) worldMapRect.getY();
            xGraphDiff += (int) worldMapRect.getX();

            return new Point(xGraphDiff, yGraphDiff);
        }
        return null;
    }

    private void addMenuEntry(MenuEntryAdded event, String option, String target, int position) {
        List<MenuEntry> entries = new LinkedList<>(Arrays.asList(client.getMenuEntries()));

        if (entries.stream().anyMatch(e -> e.getOption().equals(option) && e.getTarget().equals(target))) {
            return;
        }

        client.createMenuEntry(position)
            .setOption(option)
            .setTarget(target)
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setIdentifier(event.getIdentifier())
            .setType(MenuAction.RUNELITE)
            .onClick(this::onMenuOptionClicked);
    }

    private Widget getMinimapDrawWidget() {
        if (client.isResized()) {
            if (client.getVarbitValue(Varbits.SIDE_PANELS) == 1) {
                return client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA);
            }
            return client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_STONES_DRAW_AREA);
        }
        return client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
    }

    private Shape getMinimapClipAreaSimple() {
        Widget minimapDrawArea = getMinimapDrawWidget();

        if (minimapDrawArea == null || minimapDrawArea.isHidden()) {
            return null;
        }

        Rectangle bounds = minimapDrawArea.getBounds();

        return new Ellipse2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    public Shape getMinimapClipArea() {
        Widget minimapWidget = getMinimapDrawWidget();

        if (minimapWidget == null || minimapWidget.isHidden() || !minimapRectangle.equals(minimapRectangle = minimapWidget.getBounds())) {
            minimapClipFixed = null;
            minimapClipResizeable = null;
            minimapSpriteFixed = null;
            minimapSpriteResizeable = null;
        }

        if (client.isResized()) {
            if (minimapClipResizeable != null) {
                return minimapClipResizeable;
            }
            if (minimapSpriteResizeable == null) {
                minimapSpriteResizeable = spriteManager.getSprite(SpriteID.RESIZEABLE_MODE_MINIMAP_ALPHA_MASK, 0);
            }
            if (minimapSpriteResizeable != null) {
                minimapClipResizeable = bufferedImageToPolygon(minimapSpriteResizeable);
                return minimapClipResizeable;
            }
            return getMinimapClipAreaSimple();
        }
        if (minimapClipFixed != null) {
            return minimapClipFixed;
        }
        if (minimapSpriteFixed == null) {
            minimapSpriteFixed = spriteManager.getSprite(SpriteID.FIXED_MODE_MINIMAP_ALPHA_MASK, 0);
        }
        if (minimapSpriteFixed != null) {
            minimapClipFixed = bufferedImageToPolygon(minimapSpriteFixed);
            return minimapClipFixed;
        }
        return getMinimapClipAreaSimple();
    }

    private Polygon bufferedImageToPolygon(BufferedImage image) {
        Color outsideColour = null;
        Color previousColour;
        final int width = image.getWidth();
        final int height = image.getHeight();
        List<java.awt.Point> points = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            previousColour = outsideColour;
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int a = (rgb & 0xff000000) >>> 24;
                int r = (rgb & 0x00ff0000) >> 16;
                int g = (rgb & 0x0000ff00) >> 8;
                int b = (rgb & 0x000000ff) >> 0;
                Color colour = new Color(r, g, b, a);
                if (x == 0 && y == 0) {
                    outsideColour = colour;
                    previousColour = colour;
                }
                if (!colour.equals(outsideColour) && previousColour.equals(outsideColour)) {
                    points.add(new java.awt.Point(x, y));
                }
                if ((colour.equals(outsideColour) || x == (width - 1)) && !previousColour.equals(outsideColour)) {
                    points.add(0, new java.awt.Point(x, y));
                }
                previousColour = colour;
            }
        }
        int offsetX = minimapRectangle.x;
        int offsetY = minimapRectangle.y;
        Polygon polygon = new Polygon();
        for (java.awt.Point point : points) {
            polygon.addPoint(point.x + offsetX, point.y + offsetY);
        }
        return polygon;
    }
}
