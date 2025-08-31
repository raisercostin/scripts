package com.namekis.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.spi.FilterReply;

/**
 * A utility class to configure Logback logging based on verbosity levels(0-5).
 *
 * <li>level 0 - Normal: Only INFO and above are logged to stdout, WARN and ERROR to stderr. No detailed logs on caller, timestamp, or thread.
 * <li>level 1 - Normal Detailed: Similar to level 0, but includes detailed logs on caller, timestamp, and thread. Useful for debugging without
 * overwhelming output.
 * <li>level 2 - Info: Logs INFO and above to stdout, WARN and ERROR to stderr. Includes detailed logs on caller, timestamp, and thread.
 * <li>level 3 - Debug: Logs DEBUG and above to stdout, WARN and ERROR to stderr.
 * <li>level 4 - Trace: Logs TRACE and above to stdout, WARN and ERROR to stderr.
 * <li>level 5 - LogDebug: Logs all levels (TRACE, DEBUG, INFO, WARN, ERROR) to stdout and stderr.
 *
 * It sets up console appenders for both standard output and error streams with appropriate filters.
 * The verbosity levels range from detailed debug logs to quiet error logs.
 */
public class RichLogback {
	private static final int LEVEL5_LOGDEBUG = 5;
	private static final int LEVEL4_TRACE = 4;
	private static final int LEVEL3_DEBUG = 3;
	private static final int LEVEL2_INFO = 2;
	private static final int LEVEL1_NORMAL_DETAILED = 1;
	private static final int LEVEL0_NORMAL = 0; // nothing from logs
	private static final Logger log = LoggerFactory.getLogger(RichLogback.class);

	public static void configureLogbackByVerbosity(String categories, int verbosity, boolean quiet, boolean color, boolean debug) {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		context.reset();

		String simplePattern = color ? "%highlight(%msg) %n" : "%msg %n";
		String detailedPattern = color
				? "%-10r/%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [%-15thread] %-40logger{36} - %msg - %C.%M\\(%F:%L\\)%n"
				: "%-10r/%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%-15thread] %-40logger{36} - %msg - %C.%M\\(%F:%L\\)%n";

		// Pattern selection by verbosity: verbosity == LEVEL0_NORMAL ? simplePattern : detailedPattern;
		String pattern = debug ? detailedPattern : simplePattern;

		// STDOUT appender: INFO/DEBUG/TRACE (but not WARN/ERROR)
		PatternLayoutEncoder outEncoder = new PatternLayoutEncoder();
		outEncoder.setContext(context);
		outEncoder.setPattern(pattern);
		outEncoder.start();

		ConsoleAppender<ILoggingEvent> outAppender = new ConsoleAppender<>();
		outAppender.setContext(context);
		outAppender.setTarget("System.out");
		outAppender.setEncoder(outEncoder);
		outAppender.setWithJansi(color);

		// Accept levels for stdout: adjust by verbosity
		ThresholdFilter stdOutFilter = new ThresholdFilter();
		if (verbosity >= LEVEL4_TRACE) {
			stdOutFilter.setLevel("TRACE");
		} else if (verbosity >= LEVEL3_DEBUG) {
			stdOutFilter.setLevel("DEBUG");
		} else if (verbosity >= LEVEL0_NORMAL) {
			stdOutFilter.setLevel("INFO");
		} else {
			stdOutFilter.setLevel("WARN");
		}
		stdOutFilter.start();
		outAppender.addFilter(stdOutFilter);
		// Deny WARN on STDOUT
		LevelFilter denyWarn = new LevelFilter();
		denyWarn.setLevel(Level.WARN);
		denyWarn.setOnMatch(FilterReply.DENY);
		denyWarn.setOnMismatch(FilterReply.NEUTRAL);
		denyWarn.start();
		outAppender.addFilter(denyWarn);

		// Deny ERROR on STDOUT
		LevelFilter denyError = new LevelFilter();
		denyError.setLevel(Level.ERROR);
		denyError.setOnMatch(FilterReply.DENY);
		denyError.setOnMismatch(FilterReply.NEUTRAL);
		denyError.start();
		outAppender.addFilter(denyError);

		outAppender.start();

		// STDERR appender: WARN/ERROR only
		PatternLayoutEncoder errEncoder = new PatternLayoutEncoder();
		errEncoder.setContext(context);
		errEncoder.setPattern(pattern);
		errEncoder.start();

		ConsoleAppender<ILoggingEvent> errAppender = new ConsoleAppender<>();
		errAppender.setContext(context);
		errAppender.setTarget("System.err");
		errAppender.setEncoder(errEncoder);
		errAppender.setWithJansi(color);

		ThresholdFilter errFilter = new ThresholdFilter();
		errFilter.setLevel("WARN"); // Accept WARN and above
		errFilter.start();
		errAppender.addFilter(errFilter);

		errAppender.start();

		// Set logger level according to verbosity
		Level logLevel = Level.INFO;
		if (verbosity >= LEVEL4_TRACE)
			logLevel = Level.TRACE;
		else if (verbosity == LEVEL3_DEBUG)
			logLevel = Level.DEBUG;
		else if (verbosity == LEVEL2_INFO)
			logLevel = Level.INFO;
		else if (verbosity == LEVEL1_NORMAL_DETAILED)
			logLevel = Level.INFO;
		else if (verbosity == LEVEL0_NORMAL)
			logLevel = Level.INFO;
		else if (verbosity < LEVEL0_NORMAL && !quiet)
			logLevel = Level.WARN;
		else if (verbosity < LEVEL0_NORMAL && quiet)
			logLevel = Level.ERROR;
		else
			logLevel = Level.INFO;
		
		categories = (categories == null || categories.isEmpty()) ? org.slf4j.Logger.ROOT_LOGGER_NAME : categories;
		//foreach
		for (String category : categories.split(",")) {
			category = category.trim();
			ch.qos.logback.classic.Logger logger = context.getLogger(category);
			logger.setLevel(logLevel);
			logger.setAdditive(false); // avoid double logging
			logger.addAppender(outAppender);
			logger.addAppender(errAppender);
			log.debug("Logback configured with category {} with verbosity {}", category, verbosity);
		}
		if (verbosity >= LEVEL5_LOGDEBUG) {
			log.trace("test trace");
			log.debug("test debug");
			log.info("test info");
			log.warn("test warn");
			log.error("test error");
		}
	}
}
