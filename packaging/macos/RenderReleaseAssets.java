/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 *
 * @author veronique
 */
public final class RenderReleaseAssets {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path directory = Path.of(args[0]);
        Path icons = directory.resolve("StillWord.iconset");
        Files.createDirectories(icons);
        for (int size : new int[]{16,32,128,256,512}) {
            icon(size, icons.resolve("icon_" + size + "x" + size + ".png"));
            icon(size * 2, icons.resolve("icon_" + size + "x" + size + "@2x.png"));
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                Class<?> appClass = Class.forName("org.stillword.StillWord");
                var look = appClass.getDeclaredMethod("configureLook"); look.setAccessible(true); look.invoke(null);
                Object app = appClass.getConstructor().newInstance();
                var about = appClass.getDeclaredMethod("aboutContent"); about.setAccessible(true);
                JPanel panel = (JPanel)about.invoke(app);
                panel.setOpaque(true); panel.setBackground(new Color(246,244,237));
                panel.setBorder(BorderFactory.createEmptyBorder(26,26,26,26));
                panel.setSize(panel.getPreferredSize());
                layout(panel);
                BufferedImage preview = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = preview.createGraphics(); panel.printAll(g); g.dispose();
                ImageIO.write(preview, "png", directory.resolve("about-preview.png").toFile());
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static void icon(int size, Path output) throws Exception {
        BufferedImage image = new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 1024.0, size / 1024.0);
        g.setPaint(new GradientPaint(100,80,new Color(40,82,68),900,960,new Color(24,52,44)));
        g.fill(new RoundRectangle2D.Double(48,48,928,928,210,210));
        g.translate(195,157); g.scale(20,20);
        g.setColor(new Color(246,239,214)); g.setStroke(new BasicStroke(1.35f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        Path2D leaf=new Path2D.Double(); leaf.moveTo(16,30); leaf.curveTo(1,23,0,9,4,5); leaf.curveTo(18,7,25,18,16,30); g.draw(leaf);
        leaf.reset(); leaf.moveTo(16,30); leaf.curveTo(27,26,34,15,29,8); leaf.curveTo(21,11,15,19,16,30); g.draw(leaf);
        g.draw(new Line2D.Double(16,30,8,13)); g.dispose();
        ImageIO.write(image,"png",output.toFile());
    }
}
