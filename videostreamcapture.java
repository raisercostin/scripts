//#!/usr/bin/env jbang
//DEPS org.bytedeco:javacv-platform:1.5.8
//DEPS org.slf4j:slf4j-api:1.7.36
//DEPS ch.qos.logback:logback-classic:1.2.11

//jbang --verbose videostreamcapture.java "rtsp://<user>:<password>@192.168.1.81:554/live/stream1" PT1M

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class videostreamcapture {
    private static final Logger log = LoggerFactory.getLogger(videostreamcapture.class);

    public static void main(String... args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: jbang run videostreamcapture.java <rtsp_url> <period>");
            System.exit(1);
        }

        System.setProperty("java.awt.headless", "true");

        URI uri = URI.create(args[0]);
        String userInfo = uri.getUserInfo();
        String maskedUrl = args[0].replaceFirst(userInfo + "@", "***:***@");
        String url = args[0];
        String period = args[1];

        log.info("Starting capture for URL {} for duration {}", maskedUrl, period);

        Duration duration;
        try {
            duration = Duration.parse(period);
        } catch (DateTimeParseException e) {
            log.error("Invalid duration format: {}. Use ISO-8601 (e.g., PT1H or P1DT12H).", period);
            return;
        }
        Instant end = Instant.now().plus(duration);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url)) {
            grabber.setVideoOption("threads", "1"); // more precise without threads

            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("hwaccel", "videotoolbox");
            grabber.start();
            log.info("FFmpegFrameGrabber started.");

            Java2DFrameConverter conv = new Java2DFrameConverter();
            int counter = 0;

            while (Instant.now().isBefore(end)) {
                long loopStart = System.currentTimeMillis();

                Frame frame = grabber.grabImage();
                //Frame frame = grabber.grabFrame(true, true, true, false, false);
                //setOption("hwaccel", "videotoolbox")
                if (frame != null) {
                    log.debug("Captured frame #{}", counter);
                    BufferedImage img = conv.convert(frame);

                    String timestamp = ZonedDateTime.now(ZoneId.systemDefault()).format(fmt);
                    String name = String.format("frame-%s-%03d.jpg", timestamp, counter);

                    ImageIO.write(img, "jpg", new File(name));
                    log.debug("Saved frame to {}", name);
                    counter++;
                } else {
                    log.warn("No frame grabbed at {}", Instant.now());
                }

                long took  = System.currentTimeMillis() - loopStart;
                long sleep = 1000 - took;
                if (sleep > 0) {
                    log.debug("Sleeping for {}ms", sleep);
                    Thread.sleep(sleep);
                }
            }
            log.info("Capture completed. Total frames: {}", counter);
        }
    }
}
