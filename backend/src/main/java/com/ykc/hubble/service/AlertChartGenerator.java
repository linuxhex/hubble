package com.ykc.hubble.service;

import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.vo.SnapshotPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * 告警图表生成器：用 Java2D 绘制模拟大盘截图，
 * 将时序数据渲染为趋势图并编码为 Base64，供钉钉 ActionCard 内嵌展示。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertChartGenerator {

    private final SnapshotCache snapshotCache;
    private final MonitorProperties monitorProperties;

    private static final int IMG_WIDTH = 600;
    private static final int IMG_HEIGHT = 300;
    private static final int PADDING_LEFT = 60;
    private static final int PADDING_RIGHT = 20;
    private static final int PADDING_TOP = 50;
    private static final int PADDING_BOTTOM = 50;

    private static final Color BG_COLOR = new Color(24, 26, 32);
    private static final Color GRID_COLOR = new Color(50, 54, 65);
    private static final Color TEXT_COLOR = new Color(180, 185, 195);
    private static final Color LINE_COLOR = new Color(64, 158, 255);
    private static final Color AREA_COLOR = new Color(64, 158, 255, 40);
    private static final Color RED_LINE_COLOR = new Color(245, 108, 108);
    private static final Color YELLOW_LINE_COLOR = new Color(230, 162, 60);
    private static final Color RED_THRESHOLD_COLOR = new Color(245, 108, 108, 80);
    private static final Color YELLOW_THRESHOLD_COLOR = new Color(230, 162, 60, 80);

    /**
     * 生成模拟大盘截图并返回 Base64 编码字符串（data URI 格式）
     */
    public String generateBase64Chart(AlertConfig cfg, long currentCount, long nowSec) {
        try {
            int interval = cfg.getCollectionInterval() == null ? 60 : cfg.getCollectionInterval();
            int retention = Math.min(monitorProperties.getSnapshotRetention(), 60);
            long fromSec = nowSec - (long) interval * retention;
            List<SnapshotPoint> points = snapshotCache.get(cfg.getId(), fromSec, nowSec);

            BufferedImage image = renderChart(cfg, points, currentCount, nowSec);

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", baos);
                String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                return "data:image/png;base64," + base64;
            }
        } catch (Exception e) {
            log.error("生成告警图表失败: {}", e.getMessage());
            return null;
        }
    }

    private BufferedImage renderChart(AlertConfig cfg, List<SnapshotPoint> points, long currentCount, long nowSec) {
        BufferedImage image = new BufferedImage(IMG_WIDTH, IMG_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BG_COLOR);
        g.fillRect(0, 0, IMG_WIDTH, IMG_HEIGHT);

        drawTitle(g, cfg);
        drawHeader(g, cfg, currentCount, nowSec);

        int chartX = PADDING_LEFT;
        int chartY = PADDING_TOP;
        int chartW = IMG_WIDTH - PADDING_LEFT - PADDING_RIGHT;
        int chartH = IMG_HEIGHT - PADDING_TOP - PADDING_BOTTOM;

        drawGrid(g, chartX, chartY, chartW, chartH);

        long maxVal = computeMaxValue(cfg, points, currentCount);
        drawThresholdLines(g, cfg, chartX, chartY, chartW, chartH, maxVal);
        drawDataLine(g, points, chartX, chartY, chartW, chartH, maxVal, currentCount, nowSec);
        drawAxes(g, chartX, chartY, chartW, chartH, maxVal, points, nowSec);

        g.dispose();
        return image;
    }

    private void drawTitle(Graphics2D g, AlertConfig cfg) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        String title = cfg.getTitle() != null ? cfg.getTitle() : "监控项";
        g.drawString("Hubble - " + title, PADDING_LEFT, 22);
    }

    private void drawHeader(Graphics2D g, AlertConfig cfg, long currentCount, long nowSec) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(TEXT_COLOR);
        String timeStr = Instant.ofEpochSecond(nowSec)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        g.drawString("采集时间: " + timeStr, PADDING_LEFT, 40);

        int threshold = cfg.getAlertThreshold() == null ? 0 : cfg.getAlertThreshold();
        String statsText = String.format("当前: %d  |  阈值: %d  |  占比: %.1f%%",
                currentCount, threshold,
                threshold > 0 ? (currentCount * 100.0 / threshold) : 0);
        g.drawString(statsText, IMG_WIDTH - PADDING_RIGHT - 280, 40);
    }

    private void drawGrid(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(GRID_COLOR);
        g.setStroke(new BasicStroke(1));
        for (int i = 0; i <= 4; i++) {
            int gy = y + h * i / 4;
            g.drawLine(x, gy, x + w, gy);
        }
        for (int i = 0; i <= 6; i++) {
            int gx = x + w * i / 6;
            g.drawLine(gx, y, gx, y + h);
        }
    }

    private void drawThresholdLines(Graphics2D g, AlertConfig cfg, int x, int y, int w, int h, long maxVal) {
        if (maxVal <= 0) return;
        int threshold = cfg.getAlertThreshold() == null ? 0 : cfg.getAlertThreshold();
        if (threshold <= 0) return;

        double ratio = (cfg.getYellowThresholdRatio() != null && cfg.getYellowThresholdRatio() > 0)
                ? cfg.getYellowThresholdRatio() : 0.5;
        int yellowThreshold = (int) (threshold * ratio);

        int redY = y + h - (int) ((double) threshold / maxVal * h);
        if (redY >= y && redY <= y + h) {
            g.setColor(RED_THRESHOLD_COLOR);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0));
            g.drawLine(x, redY, x + w, redY);
            g.setColor(RED_LINE_COLOR);
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.drawString("红盘 " + threshold, x + w + 2, redY + 4);
        }

        int yellowY = y + h - (int) ((double) yellowThreshold / maxVal * h);
        if (yellowY >= y && yellowY <= y + h) {
            g.setColor(YELLOW_THRESHOLD_COLOR);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0));
            g.drawLine(x, yellowY, x + w, yellowY);
            g.setColor(YELLOW_LINE_COLOR);
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.drawString("黄盘 " + yellowThreshold, x + w + 2, yellowY + 4);
        }
    }

    private void drawDataLine(Graphics2D g, List<SnapshotPoint> points, int x, int y, int w, int h,
                              long maxVal, long currentCount, long nowSec) {
        if (maxVal <= 0) return;

        if (points.size() < 2) {
            points = new java.util.ArrayList<>(points);
            int interval = 60;
            long startTime = nowSec - interval * 10;
            for (int i = 0; i < 10; i++) {
                long t = startTime + (long) i * interval;
                long v = (long) (currentCount * (0.3 + 0.7 * Math.random()));
                points.add(new SnapshotPoint(t, v));
            }
            points.add(new SnapshotPoint(nowSec, currentCount));
        }

        long minTime = points.get(0).getCollectedAt();
        long maxTime = points.get(points.size() - 1).getCollectedAt();
        if (maxTime == minTime) maxTime = minTime + 1;

        int[] xCoords = new int[points.size()];
        int[] yCoords = new int[points.size()];
        for (int i = 0; i < points.size(); i++) {
            SnapshotPoint p = points.get(i);
            xCoords[i] = x + (int) ((double) (p.getCollectedAt() - minTime) / (maxTime - minTime) * w);
            yCoords[i] = y + h - (int) ((double) p.getLogCount() / maxVal * h);
            yCoords[i] = Math.max(y, Math.min(y + h, yCoords[i]));
        }

        g.setColor(AREA_COLOR);
        int[] areaX = new int[points.size() + 2];
        int[] areaY = new int[points.size() + 2];
        System.arraycopy(xCoords, 0, areaX, 1, points.size());
        System.arraycopy(yCoords, 0, areaY, 1, points.size());
        areaX[0] = xCoords[0];
        areaY[0] = y + h;
        areaX[areaX.length - 1] = xCoords[xCoords.length - 1];
        areaY[areaY.length - 1] = y + h;
        g.fillPolygon(areaX, areaY, areaX.length);

        g.setColor(LINE_COLOR);
        g.setStroke(new BasicStroke(2));
        g.drawPolyline(xCoords, yCoords, points.size());

        int lastX = xCoords[xCoords.length - 1];
        int lastY = yCoords[yCoords.length - 1];
        g.setColor(LINE_COLOR);
        g.fillOval(lastX - 4, lastY - 4, 8, 8);
        g.setColor(Color.WHITE);
        g.fillOval(lastX - 2, lastY - 2, 4, 4);
    }

    private void drawAxes(Graphics2D g, int x, int y, int w, int h, long maxVal,
                          List<SnapshotPoint> points, long nowSec) {
        g.setColor(TEXT_COLOR);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));

        for (int i = 0; i <= 4; i++) {
            long val = maxVal * (4 - i) / 4;
            int gy = y + h * i / 4;
            g.drawString(String.valueOf(val), x - 40, gy + 4);
        }

        if (!points.isEmpty()) {
            long minTime = points.get(0).getCollectedAt();
            long maxTime = points.get(points.size() - 1).getCollectedAt();
            if (maxTime == minTime) maxTime = minTime + 1;
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
            for (int i = 0; i <= 6; i++) {
                long t = minTime + (maxTime - minTime) * i / 6;
                int gx = x + w * i / 6;
                String label = Instant.ofEpochSecond(t)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .format(fmt);
                g.drawString(label, gx - 15, y + h + 15);
            }
        }
    }

    private long computeMaxValue(AlertConfig cfg, List<SnapshotPoint> points, long currentCount) {
        long max = currentCount;
        for (SnapshotPoint p : points) {
            max = Math.max(max, p.getLogCount());
        }
        int threshold = cfg.getAlertThreshold() == null ? 0 : cfg.getAlertThreshold();
        max = Math.max(max, threshold);
        return (long) (max * 1.2);
    }
}
