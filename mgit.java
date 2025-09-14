//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS org.slf4j:slf4j-api:2.0.9
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS one.util:streamex:0.8.2
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.17.1
//SOURCES com/namekis/utils/RichLogback.java

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.namekis.utils.RichLogback;

import one.util.streamex.StreamEx;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public class mgit {
  private static final String DEFAULT_BRANCH = "~DEFAULT";
  static final String description = """
      mgit - Multi-repo Git Tool - Allow to change multiple repos in the same way:  status, branch, commit messages, push, prs.

      HISTORY:
        2024-06: Initial version (multi-repo status, commit, push, checkout).
        2024-08: Adds PR link detection, default branch tracking, metadata in git config.
        2025-08: Unified --repos/--exclude filtering, status color legend, rich logging.

      EXAMPLES:
        scoop install jbang                                                            # Install jbang via scoop
        jbang https://github.com/raisercostin/scripts/blob/main/mgit.java -h           # run the script from GitHub without installing
        jbang app install https://github.com/raisercostin/scripts/blob/main/mgit.java  # install mgit app

        mgit status --repos=foo,bar            # Show status for foo and bar only
        mgit checkout -b feature/foo """ + DEFAULT_BRANCH + """
      # New branch from default remote branch
               mgit push --force-with-lease           # Push with force protection
               mgit uprebase                          # Rebase local branch on remote default

             NOTES:
               - All commands support --repos and --exclude (comma-separated).
               - PR creation and merge states tracked in git config (mgit.pr.<branch>.link/state).
               - Output is always ASCII only. Colors follow Picocli conventions.
               - Run 'mgit status --show-legend' to display status legend.

             Full docs: https://github.com/yourrepo/mgit
             Report bugs: https://github.com/yourrepo/mgit/issues
            """;

  static final Logger log = LoggerFactory.getLogger(mgit.class);

  public static void main(String... args) {
    int exitCode = new CommandLine(new MgitRoot()).execute(args);
    System.exit(exitCode);
  }

  public static void stdout(String msg) {
    System.out.println(Ansi.AUTO.string(msg));
  }

  public static void stdoutf(String format, Object... args) {
    System.out.println(Ansi.AUTO.string(format.formatted(args)));
  }

  @Command(name = "mgit", mixinStandardHelpOptions = true, version = "mgit 0.1", description = description, subcommands = { MgitCheckout.class,
      Status.class, Commit.class, Push.class, Uprebase.class, PrCreated.class, PrMerged.class, Resolve.class, Fetch.class }, sortOptions = false)
  public static class MgitRoot extends MGitCommon implements Runnable {
    static final Logger log = LoggerFactory.getLogger(MgitRoot.class);

    @Override
    public void run() {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      new CommandLine(this).usage(System.out);
    }
  }

  public abstract static class CommonOptions {
    @Option(names = { "-v", "--verbose" }, description = "Increase verbosity. Specify multiple times to increase (-vvv).")
    boolean[] verbosity = new boolean[0];

    @Option(names = { "-q", "--quiet" }, description = "Suppress all output except errors.")
    boolean quiet = false;

    @Option(names = { "-c",
        "--color" }, description = "Enable colored output (default: true).", defaultValue = "true", showDefaultValue = Visibility.ALWAYS)
    public boolean color = true;

    @Option(names = { "-d", "--debug" }, description = "Enable debug (default: false).", defaultValue = "false", showDefaultValue = Visibility.ALWAYS)
    public boolean debug = false;
  }

  public abstract static class MGitCommon extends CommonOptions {
    static final Logger log = LoggerFactory.getLogger(MgitRoot.class);
    @Option(names = "--exclude", split = ",", description = "Comma-separated list of repo names to exclude (by folder name)")
    public List<String> exclude = new ArrayList<>();

    @Option(names = "--repos", description = "Comma-separated list of subdirectories to scan (default: all subdirs with .git)")
    public String repos;

    public List<File> findRepos() {
      log.debug("findRepos(): repos option = '{}', exclude = '{}'", repos, exclude);
      try {
        Path start = new File(".").getCanonicalFile().toPath();
        Path root = gitRootOrSelf(start);
        if (!root.equals(start)) {
          log.info("Ascended to repo root: {}", root.toAbsolutePath());
        } else {
          log.info("Scanning from: {}", root.toAbsolutePath());
        }

        Set<String> repoNames = (repos != null && !repos.isBlank())
            ? StreamEx.of(repos.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toSet()
            : null;

        List<File> found;
        try (Stream<Path> s = Files.walk(root, 3)) {
          found = s.filter(Files::isDirectory).filter(p -> Files.exists(p.resolve(".git"))) // .git file or dir
              .filter(p -> repoNames == null || repoNames.contains(p.getFileName().toString())).map(p -> {
                try {
                  return p.toFile().getCanonicalFile();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              }).toList();
        }

        return found.stream().filter(f -> exclude == null || exclude.isEmpty() || !exclude.contains(f.getName()))
            .sorted(Comparator.comparing(File::getAbsolutePath)).toList();
      } catch (IOException | UncheckedIOException e) {
        throw new RuntimeException("Failed to find repos: " + e.getMessage(), e);
      }
    }

    private static Path gitRootOrSelf(Path start) {
      for (Path p = start; p != null; p = p.getParent()) {
        if (Files.exists(p.resolve(".git")))
          return p;
      }
      return start;
    }
  }

  public abstract static class MGitWritableCommon extends MGitCommon {
    @Option(names = "--dry-run", description = "Show what would be done, but don't make changes.")
    public boolean dryRun;
  }

  @Command(name = "checkout", description = "Switch or create a branch in all repos. Use -b for new branch. '" + DEFAULT_BRANCH
      + "' source uses remote default branch.")
  public static class MgitCheckout extends MGitWritableCommon implements Callable<Integer> {

    @Option(names = "-b", description = "Branch name to create and checkout (will track source).")
    String newBranch;

    @Parameters(index = "0", description = "Source branch/ref/commit. Use '" + DEFAULT_BRANCH + "' for remote default branch.")
    String from;

    @Option(names = "--force-fetch", description = "If remote tracking ref is missing, fetch that branch before checkout.")
    boolean forceFetch;

    @Option(names = "--autostash", description = "Temporarily stash local changes before switching/creating, then reapply.")
    boolean autostash;

    enum CheckoutResult {
      UNCHANGED, UNCHANGED2, CHANGED, WARNED
    }

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);

      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      var changes = new EnumMap<CheckoutResult, AtomicInteger>(CheckoutResult.class);
      for (File repo : repoDirs) {
        CheckoutResult ok = checkout(repo, newBranch, from);
        changes.computeIfAbsent(ok, k -> new AtomicInteger(0)).incrementAndGet();
      }
      var all = StreamEx.of(changes.entrySet()).filter(x -> x.getValue().get() > 0).joining(",");
      log.info("Processed repos. {}", all);
      return changes.size() == 1 && changes.get(CheckoutResult.UNCHANGED) != null ? 0 : 1;
    }

    private CheckoutResult checkout(File repo, String localBranch, String from) {
      String effectiveFrom = DEFAULT_BRANCH.equalsIgnoreCase(from) ? getRemoteDefaultBranch(repo) : from;
      String currentBranch = getCurrentBranch(repo);
      if (currentBranch != null && currentBranch.equals(effectiveFrom) && (localBranch == null || localBranch.isBlank())) {
        log.info("[{}] Already on '{}', nothing to do.", repo.getName(), effectiveFrom);
        return CheckoutResult.UNCHANGED;
      }

      if (localBranch != null && !localBranch.isBlank()) {
        // Create new local branch from source
        if (localBranchExists(repo, localBranch)) {
          log.warn("[{}] Local branch '{}' already exists. Skipping.", repo.getName(), localBranch);
          return CheckoutResult.UNCHANGED2;
        }
        // prefer remote tracking ref if present locally (optionally fetch it if --force-fetch)
        String sourceRef;
        if (ensureLocalRemoteTracking(repo, effectiveFrom)) {
          sourceRef = "origin/" + effectiveFrom;
        } else {
          // fall back to local ref/commit name (could be tag/sha/branch)
          sourceRef = effectiveFrom;
        }
        // int exit = runGitExitCode("checkout-new", repo, "checkout", "-b", localBranch, sourceRef);
        boolean ok = attemptCheckoutWithAutostash(repo, "checkout-new", "checkout", "-b", localBranch, sourceRef);
        if (ok) {
          if (sourceRef.startsWith("origin/")) {
            runGitExitCode("set-upstream", repo, "branch", "--set-upstream-to", sourceRef, localBranch);
          }
          log.info("[{}] created branch '{}' from '{}' and set upstream", repo.getName(), localBranch, sourceRef);
          return CheckoutResult.CHANGED;
        } else {
          log.warn("[{}] Could not create branch '{}' from '{}'. Try: mgit checkout -b {} {} --repos={}{}", repo.getName(), localBranch, sourceRef,
              localBranch, DEFAULT_BRANCH, repo.getName(), autostash ? "" : "  or add --autostash");
          return CheckoutResult.WARNED;
        }
      } else {
        // Switch semantics
        if (localBranchExists(repo, effectiveFrom)) {
          // int exit = runGitExitCode("checkout-local", repo, "checkout", effectiveFrom);
          boolean ok = attemptCheckoutWithAutostash(repo, "checkout-local", "checkout", effectiveFrom);
          if (ok) {
            log.info("[{}] switched to '{}'", repo.getName(), effectiveFrom);
            return CheckoutResult.CHANGED;
          } else {
            log.warn("[{}] Failed to switch to '{}'. Remain on '{}'.{}", repo.getName(), effectiveFrom, currentBranch,
                autostash ? "" : " Consider --autostash");
            return CheckoutResult.WARNED;
          }
        }
        if (ensureLocalRemoteTracking(repo, effectiveFrom)) {
          // int exit = runGitExitCode("checkout-track", repo, "checkout", "--track", "origin/" + effectiveFrom);
          boolean ok = attemptCheckoutWithAutostash(repo, "checkout-track", "checkout", "--track", "origin/" + effectiveFrom);
          if (ok) {
            log.info("[{}] tracking 'origin/{}'", repo.getName(), effectiveFrom);
            return CheckoutResult.CHANGED;
          } else {
            log.warn("[{}] Unable to track remote 'origin/{}'. Remain on `{}`. Try: mgit checkout -b {} {} --repos={}{}", repo.getName(),
                effectiveFrom, currentBranch, effectiveFrom, DEFAULT_BRANCH, repo.getName(), autostash ? "" : "  or add --autostash");
            return CheckoutResult.WARNED;
          }
        }
        // Neither local nor remote exists
        log.warn("[{}] Branch '{}' does not exist. Remain on '{}'. To create from default: mgit checkout -b {} {} --repos={}", repo.getName(),
            effectiveFrom, currentBranch, effectiveFrom, DEFAULT_BRANCH, repo.getName());
        return CheckoutResult.WARNED;
      }
    }

    private boolean ensureLocalRemoteTracking(File repo, String branch) {
      // Check local remote-tracking ref; do NOT hit network unless user asked
      if (remoteBranchExists(repo, branch))
        return true;
      if (this.forceFetch) {
        return fetchRemoteBranch(repo, branch) && remoteBranchExists(repo, branch);
      }
      return false;
    }

    private static boolean fetchRemoteBranch(File repo, String branch) {
      // Targeted fetch: origin <branch> into local remote-tracking ref
      int exit = runGitExitCode("fetch-branch", repo, "fetch", "origin", branch + ":refs/remotes/origin/" + branch);
      if (exit == 0) {
        return true;
      }
      // 128: couldn't find remote ref; 1/2: generic failure. Treat as "not available" and continue.
      log.debug("[{}] fetch-branch for '{}' returned exit {} (likely missing on remote).", repo.getName(), branch, exit);
      return false;
    }

    private static boolean localBranchExists(File repo, String branch) {
      int exit = runGitExitCode("branch-local-exists", repo, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
      return exit == 0;
    }

    private static String getRemoteDefaultBranch(File repo) {
      String out = runGitCommand("remote-default-branch", repo, "symbolic-ref", "refs/remotes/origin/HEAD");
      final String prefix = "refs/remotes/origin/";
      return out.startsWith(prefix) ? out.substring(prefix.length()) : out;
    }

    private static boolean hasLocalChanges(File repo) {
      String out = runGitCommand("status-porcelain", repo, "status", "--porcelain");
      return out != null && !out.isBlank();
    }

    private static boolean gitStashPush(File repo, String msg) {
      int exit = runGitExitCode("stash-push", repo, "stash", "push", "-u", "-m", msg);
      return exit == 0;
    }

    private static boolean gitStashPop(File repo) {
      int exit = runGitExitCode("stash-pop", repo, "stash", "pop");
      return exit == 0;
    }

    /** Try checkout; if it fails and --autostash is set and there are local changes, stash -> retry -> pop. */
    private boolean attemptCheckoutWithAutostash(File repo, String opName, String... args) {
      int exit = runGitExitCode(opName, repo, args);
      if (exit == 0)
        return true;

      if (!autostash || !hasLocalChanges(repo))
        return false;

      String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss").format(new java.util.Date());
      String msg = "mgit-autostash " + stamp;
      if (!gitStashPush(repo, msg))
        return false;

      int exit2 = runGitExitCode(opName + "-retry", repo, args);
      if (exit2 == 0) {
        boolean popped = gitStashPop(repo);
        if (!popped) {
          log.warn("[{}] Autostash applied with conflicts. Resolve and 'git stash drop' if needed.", repo.getName());
        }
        return true;
      }
      // checkout still failed; keep the stash to avoid data loss
      log.warn("[{}] Checkout failed even after autostash. Stashed changes kept (use 'git stash list').", repo.getName());
      return false;
    }

  }

  static class StatusCounts {
    public int dirty = 0;
    public int ahead = 0;
    public int behind = 0;
    public int local = 0;
    public int clean = 0;
    public int conflicted = 0;
  }

  @Command(name = "status", description = "Show git status for all repos (with color, short summary)")
  public static class Status extends MGitCommon implements Callable<Integer> {

    static final Logger log = LoggerFactory.getLogger(Status.class);

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      StatusCounts counts = new StatusCounts();
      for (File repo : repoDirs) {
        RepoStatus rs = computeRepoStatus(repo);

        StringBuilder tags = new StringBuilder();
        String blue = "\u001B[94m";
        String green = "\u001B[32m";
        String red = "\u001B[31m";
        String yellow = "\u001B[33m";
        String magenta = "\u001B[35m";
        String cyan = "\u001B[36m";
        String reset = "\u001B[0m";
        if (rs.conflicted) {
          tags.append(red).append("[CONFLICT]").append(reset);
        } else {
          if (rs.dirty)
            tags.append(red).append("[DIRTY]").append(reset);
          if (rs.ahead > 0)
            tags.append(yellow).append("[AHEAD ").append(rs.ahead).append("]").append(reset);
          if (rs.behind > 0)
            tags.append(magenta).append("[BEHIND ").append(rs.behind).append("]").append(reset);
          if (rs.onlyLocal && !rs.dirty)
            tags.append(cyan).append("[LOCAL]").append(reset);
          if (!rs.dirty && rs.ahead == 0 && rs.behind == 0 && !rs.onlyLocal)
            tags.append(green).append("[CLEAN]").append(reset);
        }

        String header = String.format("%s%s/%s/#%s%s %s", blue, repo.getName(), rs.branch, rs.shortHash, reset, tags);

        System.out.println(rs.dirty ? header + "\n" + rs.dirtyFiles : header);
        String prLink = getPrLink(repo, rs.branch);
        if (prLink != null && !prLink.isBlank()) {
          String prState = getPrState(repo, rs.branch);
          System.out.print("  PR: " + prLink);
          if (prState != null) {
            System.out.print(" [" + prState.toUpperCase() + "]");
            if ("merged".equalsIgnoreCase(prState)) {
              System.out.print(" (local branch can be deleted, consider checking out default)");
            }
          }
          System.out.println();
        }
        // Update counts
        if (rs.conflicted) {
          counts.conflicted++;
        } else if (rs.dirty) {
          counts.dirty++;
        } else if (rs.ahead > 0) {
          counts.ahead++;
        } else if (rs.behind > 0) {
          counts.behind++;
        } else if (rs.onlyLocal) {
          counts.local++;
        } else {
          counts.clean++;
        }
      }
      printStatusSummary(counts, true);
      return 0;
    }

    static void updateStatusCounts(StatusCounts counts, StatusState state) {
      if (state.dirty) {
        counts.dirty++;
      } else if (state.ahead > 0) {
        counts.ahead++;
      } else if (state.behind > 0) {
        counts.behind++;
      } else if (state.onlyLocal) {
        counts.local++;
      } else {
        counts.clean++;
      }
    }

    @Deprecated // not used observed at 2025-09-13
    private String getShortStatus(File repo) {
      try {
        String shortHash = getShortHash(repo);
        String branch = getBranchOrDetached(repo, shortHash);
        StatusState state = getRepoState(repo);

        String blue = "\u001B[94m";
        String green = "\u001B[32m";
        String red = "\u001B[31m";
        String yellow = "\u001B[33m";
        String magenta = "\u001B[35m";
        String cyan = "\u001B[36m";
        String reset = "\u001B[0m";

        StringBuilder tags = new StringBuilder();
        if (state.dirty)
          tags.append(red).append("[DIRTY]").append(reset);
        if (state.ahead > 0)
          tags.append(yellow).append("[AHEAD ").append(state.ahead).append("]").append(reset);
        if (state.behind > 0)
          tags.append(magenta).append("[BEHIND ").append(state.behind).append("]").append(reset);
        if (state.onlyLocal && !state.dirty)
          tags.append(cyan).append("[LOCAL]").append(reset);
        if (!state.dirty && state.ahead == 0 && state.behind == 0 && !state.onlyLocal)
          tags.append(green).append("[CLEAN]").append(reset);

        String header = String.format("%s%s/%s/#%s%s %s", blue, repo.getName(), branch, shortHash, reset, tags);

        if (!state.dirty)
          return header;

        String files = StreamEx.of(state.dirtyFiles.split("\r?\n")).map(l -> "  " + l.trim()).joining("\n");
        return header + "\n" + files;
      } catch (Exception e) {
        log.error("Failed git status in {}: {}", repo, e.toString());
        return "[ERROR]";
      }
    }

    static class StatusState {
      boolean dirty;
      boolean conflicted = false;
      int ahead;
      int behind;
      boolean onlyLocal = false;
      String dirtyFiles = "";
      int defaultAhead;
      int defaultBehind;
    }

    @Deprecated // not used observed at 2025-09-13
    private static StatusState getRepoState(File repo) {
      StatusState state = new StatusState();
      state.dirtyFiles = getPorcelainStatus(repo);
      state.dirty = !state.dirtyFiles.isEmpty();

      // Detect conflicts from porcelain codes (U?, ?U, AA, DD, UU, etc.)
      state.conflicted = StreamEx.of(state.dirtyFiles.split("\\r?\\n")).anyMatch(line -> {
        if (line.isBlank())
          return false;
        String code = line.substring(0, Math.min(2, line.length())).trim();
        return code.matches("U.|.U|AA|DD|UU");
      });
      log.info("here:" + state.conflicted);

      // Ahead/behind vs upstream
      try {
        String trackingOutput = runGitCommand("repoState", repo, "rev-list", "--left-right", "--count", "HEAD...@{u}");
        String[] parts = trackingOutput.split("\\s+");
        if (parts.length == 2) {
          state.ahead = Integer.parseInt(parts[0]);
          state.behind = Integer.parseInt(parts[1]);
        }
      } catch (Exception ex) {
        String msg = ex.getMessage();
        if (msg != null
            && (msg.contains("no upstream configured for branch") || msg.contains("no upstream branch") || msg.contains("fatal: no upstream"))) {
          state.onlyLocal = true;
        } else {
          throw new RuntimeException("Tracking unavailable for repo " + repo.getName(), ex);
        }
      }
      return state;
    }

    static String getDefaultBranch(File repo) {
      try {
        String out = runGitCommand("default", repo, "symbolic-ref", "refs/remotes/origin/HEAD");
        final String prefix = "refs/remotes/origin/";
        return out.startsWith(prefix) ? out.substring(prefix.length()) : out;
      } catch (Exception e) {
        return "main";
      }
    }

    static String getAheadBehind(File repo, String branch, String baseBranch) {
      try {
        String out = runGitCommand("aheadBehind", repo, "rev-list", "--left-right", "--count", branch + "..." + baseBranch);
        String[] parts = out.split("\\s+");
        if (parts.length == 2) {
          int ahead = Integer.parseInt(parts[0]);
          int behind = Integer.parseInt(parts[1]);
          List<String> status = new ArrayList<>();
          if (ahead > 0)
            status.add("ahead " + ahead);
          if (behind > 0)
            status.add("behind " + behind);
          return String.join(", ", status);
        }
        return "";
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    static String getShortHash(File repo) {
      return runGitCommand("shortHash", repo, "rev-parse", "--short=8", "HEAD");
    }

    static String getBranchOrDetached(File repo, String shortHash) {
      try {
        String branch = runGitCommand("branch", repo, "symbolic-ref", "--short", "HEAD");
        if (!branch.isEmpty())
          return branch;
        log.error("symbolic-ref returned empty string for {}", repo);
        throw new RuntimeException("Branch unavailable");
      } catch (Exception ex) {
        String msg = ex.getMessage();
        if (msg != null && msg.contains("fatal: ref HEAD is not a symbolic ref")) {
          return "(detached:" + shortHash + ")";
        }
        log.error("Error getting branch in {}: {}", repo, msg, ex);
        throw new RuntimeException("Branch unavailable");
      }
    }

    static String getTracking(File repo) {
      try {
        String trackingOutput = runGitCommand("tracking", repo, "rev-list", "--left-right", "--count", "HEAD...@{u}");
        String[] parts = trackingOutput.split("\\s+");
        if (parts.length == 2) {
          int ahead = Integer.parseInt(parts[0]);
          int behind = Integer.parseInt(parts[1]);
          if (ahead > 0 || behind > 0) {
            List<String> status = new ArrayList<>();
            if (ahead > 0)
              status.add("ahead " + ahead);
            if (behind > 0)
              status.add("behind " + behind);
            return String.join(", ", status);
          }
          return "";
        } else {
          log.error("Unexpected tracking output in {}: '{}'", repo, trackingOutput);
          throw new RuntimeException("Tracking unavailable");
        }
      } catch (Exception ex) {
        String msg = ex.getMessage();
        if (msg != null
            && (msg.contains("no upstream configured for branch") || msg.contains("no upstream branch") || msg.contains("fatal: no upstream"))) {
          return "";
        }
        log.error("Error getting tracking info in {}: {}", repo, msg, ex);
        throw new RuntimeException("Tracking unavailable");
      }
    }

    static String getPorcelainStatus(File repo) {
      return runGitCommand("porcelainStatus", repo, "status", "--porcelain");
    }

    static void printStatusSummary(StatusCounts counts, boolean showLegend) {
      record Entry(String label, String color, int count, String description) {
      }
      List<Entry> order = List.of(
          new Entry("[DIRTY]", "red", counts.dirty, "Uncommitted local changes. Use 'mgit commit -am <msg>' or 'mgit stash'."),
          new Entry("[CONFLICT]", "red", counts.conflicted, "Unmerged/conflicted state. Resolve conflicts before continuing."),
          new Entry("[AHEAD]", "yellow", counts.ahead, "Local commits to push. Use 'mgit push'."),
          new Entry("[BEHIND]", "magenta", counts.behind, "Remote has commits to pull. Use 'mgit pull' or 'mgit fetch'."),
          new Entry("[LOCAL]", "cyan", counts.local, "Branch exists only locally (no remote). Use 'mgit push -u origin <branch>'."),
          new Entry("[CLEAN]", "green", counts.clean, "Branch is up to date with remote. No action needed."));

      for (Entry entry : order) {
        if (entry.count() == 0)
          continue;
        String legend = showLegend ? "   : " + entry.description() : "";
        System.out.println(Ansi.AUTO.string(String.format("@|%s %s|@ (%d repos)%s", entry.color(), entry.label(), entry.count(), legend)));
      }
    }
  }

  @Command(name = "commit", description = "Commit all dirty submodules with a message")
  public static class Commit extends MGitWritableCommon implements Callable<Integer> {
    @Option(names = { "-am", "--message" }, required = true, description = "Commit message")
    String message;

    final static Logger log = LoggerFactory.getLogger(Commit.class);

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      int committed = 0, skipped = 0, errors = 0;
      for (File repo : repoDirs) {
        if (isDetached(repo)) {
          log.warn("Repo '{}' is in detached HEAD, skipping.", repo.getName());
          skipped++;
          continue;
        }
        if (!hasChanges(repo)) {
          log.debug("Repo '{}' is clean, skipping.", repo.getName());
          skipped++;
          continue;
        }
        int rc = gitCommit(repo, message);
        if (rc == 0) {
          stdoutf("@|green [%s] committed|@", repo.getName());
          committed++;
        } else {
          log.error("Failed to commit in '{}'.", repo.getName());
          errors++;
        }
      }
      log.info("Committed: {}, Skipped: {}, Errors: {}", committed, skipped, errors);
      return errors > 0 ? 1 : 0;
    }

    boolean hasChanges(File repo) {
      try {
        String out = runGitCommand("changes", repo, "status", "--porcelain");
        return !out.isEmpty();
      } catch (Exception e) {
        log.error("Failed git status in {}: {}", repo, e.toString());
        return false;
      }
    }

    boolean isDetached(File repo) {
      try {
        String out = runGitCommand("detached", repo, "symbolic-ref", "--short", "-q", "HEAD");
        return out.isEmpty();
      } catch (Exception e) {
        log.warn("Failed to check HEAD for {}: {}", repo, e.toString());
        return true;
      }
    }

    int gitCommit(File repo, String msg) {
      try {
        runGitCommand("add", repo, "add", "-A");
        runGitCommand("commit", repo, "commit", "-am", msg);
        return 0;
      } catch (Exception e) {
        log.error("Commit failed for {}: {}", repo, e.toString());
        return 1;
      }
    }
  }

  @Command(name = "push", description = "Push current branch for all submodules. If branch is not tracked, will use 'git push -u origin <branch>'.")
  public static class Push extends MGitWritableCommon implements Callable<Integer> {
    final static Logger log = LoggerFactory.getLogger(Push.class);
    @Option(names = "--force-with-lease", description = "Force push with lease (git push --force-with-lease)")
    private boolean forceWithLease;

    @Option(names = "--force", description = "Force push (git push --force). Use with caution.")
    private boolean force;

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      int pushed = 0, skipped = 0, errors = 0;

      for (File repo : repoDirs) {
        String branch = getCurrentBranch(repo);
        if (branch == null) {
          log.warn("Repo '{}' is in detached HEAD, skipping.", repo.getName());
          skipped++;
          continue;
        }
        if (isNothingToPush(repo)) {
          log.debug("Repo '{}' has nothing to push, skipping.", repo.getName());
          skipped++;
          continue;
        }
        boolean hasUpstream = hasUpstream(repo, branch);

        List<String> cmd;
        if (hasUpstream) {
          cmd = new ArrayList<>(List.of("push"));
        } else {
          cmd = new ArrayList<>(List.of("push", "-u", "origin", branch));
        }
        if (forceWithLease) {
          cmd.add("--force-with-lease");
        } else if (force) {
          cmd.add("--force");
        }
        try {
          String pushOutput = runGitCommand("push", repo, cmd.toArray(new String[0]));
          // Detect PR link
          Pattern prLinkPattern = Pattern.compile("https?://[^\\s]+/pull-requests\\?[^\\s]+");
          Matcher m = prLinkPattern.matcher(pushOutput);
          if (m.find()) {
            String url = m.group();
            setPrLink(repo, branch, url);
            stdoutf("@|cyan [%s] PR link: %s|@", repo.getName(), url);
          }
          stdoutf("@|green [%s] pushed (%s)%s|@", repo.getName(), branch, hasUpstream ? "" : " [set-upstream]");
          pushed++;
        } catch (Exception ex) {
          log.error("Push failed in '{}': {}", repo.getName(), ex.getMessage());
          errors++;
        }
      }
      log.info("Pushed: {}, Skipped: {}, Errors: {}", pushed, skipped, errors);
      return errors > 0 ? 1 : 0;
    }

    boolean isNothingToPush(File repo) {
      try {
        String trackingOutput = runGitCommand("isNothingToPush", repo, "rev-list", "--left-right", "--count", "HEAD...@{u}");
        String[] parts = trackingOutput.split("\\s+");
        if (parts.length == 2) {
          int ahead = Integer.parseInt(parts[0]);
          return ahead == 0;
        }
        return false;
      } catch (Exception e) {
        log.debug("Failed to check if nothing to push in {}: {}", repo, e.toString());
        return false;
      }
    }

    boolean hasUpstream(File repo, String branch) {
      try {
        String upstream = runGitCommand("hasUpstream", repo, "rev-parse", "--abbrev-ref", branch + "@{u}");
        return !upstream.isEmpty();
      } catch (Exception e) {
        return false;
      }
    }
  }

  private static int runGitExitCode(String operation, File repo, String... cmd) {
    java.util.List<String> cmdList = new java.util.ArrayList<>();
    cmdList.add("git");
    cmdList.add("-C");
    cmdList.add(repo.getAbsolutePath());
    for (String s : cmd)
      cmdList.add(s);
    String printableCmd = String.join(" ", cmdList);
    log.debug("run {}: {}", operation, printableCmd);
    try {
      org.zeroturnaround.exec.ProcessResult r = new org.zeroturnaround.exec.ProcessExecutor().command(cmdList).redirectErrorStream(true)
          .readOutput(true)
          // NOTE: no .exitValues(...) — we never throw on exit code
          .execute();
      String out = r.outputUTF8().trim();
      if (!out.isEmpty()) {
        log.debug("output {}:\n{}", operation, out);
      }
      return r.getExitValue();
    } catch (Exception e) {
      // Genuine execution failure (git missing, IO issue). Do not use for flow control.
      throw new RuntimeException("Failed exec on %s: [%s] %s".formatted(operation, printableCmd, e.getMessage()), e);
    }
  }

  static String runGitCommand(String operation, File repo, String... cmd) {
    return runGitCommand(false, operation, repo, cmd);
  }

  static String runGitCommand(boolean showCmd, String operation, File repo, String... cmd) {
    List<String> cmdList = new ArrayList<>();
    cmdList.add("git");
    cmdList.add("-C");
    cmdList.add(repo.getAbsolutePath());
    for (String s : cmd)
      cmdList.add(s);
    String printableCmd = String.join(" ", cmdList);
    if (showCmd) {
      stdoutf("@|cyan # %s|@", printableCmd);
    } else {
      log.debug("run {}: {}", operation, printableCmd);
    }
    try {
      org.zeroturnaround.exec.ProcessExecutor proc = new org.zeroturnaround.exec.ProcessExecutor().command(cmdList).readOutput(true)
          .exitValueNormal();
      String output = proc.execute().outputUTF8().trim();
      log.debug("output {}:\n{}", operation, output);
      return output;
    } catch (Exception e) {
      throw new RuntimeException("Failed on %s: [%s] %s".formatted(operation, printableCmd, e.getMessage()), e);
    }
  }

  static String getCurrentBranch(File repo) {
    String branch = runGitCommand("", repo, "symbolic-ref", "--short", "HEAD");
    return branch.isEmpty() ? null : branch;
  }

  public static class RepoStatus {
    public boolean conflicted;
    String branch;
    String shortHash;
    boolean dirty;
    String dirtyFiles;
    int ahead;
    int behind;
    boolean onlyLocal;
  }

  static RepoStatus computeRepoStatus(File repo) {
    RepoStatus rs = new RepoStatus();

    // Run "git status --branch --porcelain"
    String statusOutput = runGitCommand(true, "status", repo, "status", "--branch", "--porcelain");
    String[] lines = statusOutput.split("\\r?\\n");
    rs.dirty = lines.length > 1;
    rs.dirtyFiles = rs.dirty ? StreamEx.of(lines).skip(1).joining("\n") : "";
    // Detect conflicts from porcelain codes
    rs.conflicted = StreamEx.of(lines).skip(1).anyMatch(line -> {
      if (line.isBlank())
        return false;
      String code = line.substring(0, Math.min(2, line.length())).trim();
      return code.matches("U.|.U|AA|DD|UU");
    });

    // Parse first line for branch and ahead/behind/local
    if (lines.length > 0 && lines[0].startsWith("##")) {
      String header = lines[0].substring(2).trim();
      // Example: "main...origin/main [ahead 1, behind 2]"
      int dotIdx = header.indexOf("...");
      int spaceIdx = header.indexOf(' ');
      if (dotIdx >= 0) {
        rs.branch = header.substring(0, dotIdx);
        String afterDots = header.substring(dotIdx + 3);
        if (afterDots.startsWith("origin/")) {
          // skip remote, check for [ahead/behind]
          int bracketIdx = afterDots.indexOf('[');
          if (bracketIdx >= 0) {
            String status = afterDots.substring(bracketIdx + 1, afterDots.length() - 1);
            for (String token : status.split(",")) {
              token = token.trim();
              if (token.startsWith("ahead")) {
                rs.ahead = Integer.parseInt(token.substring(6));
              } else if (token.startsWith("behind")) {
                rs.behind = Integer.parseInt(token.substring(7));
              }
            }
          }
        }
      } else if (spaceIdx > 0) {
        // Example: "main [gone]"
        rs.branch = header.substring(0, spaceIdx);
        if (header.contains("no upstream")) {
          rs.onlyLocal = true;
        }
      } else {
        rs.branch = header;
      }
      if (header.contains("no upstream")) {
        rs.onlyLocal = true;
      }
    } else {
      rs.branch = "(unknown)";
    }

    // If neither ahead nor behind, and onlyLocal not set, try to detect local-only
    // via '[gone]' or 'no upstream'
    if (!rs.onlyLocal && (rs.ahead == 0 && rs.behind == 0) && lines.length > 0) {
      String header = lines[0];
      if (header.contains("no upstream") || header.contains("[gone]")) {
        rs.onlyLocal = true;
      }
    }

    // Get short hash (separate command)
    try {
      rs.shortHash = runGitCommand("shortHash", repo, "rev-parse", "--short=8", "HEAD");
    } catch (Exception ex) {
      rs.shortHash = "--------";
    }
    return rs;
  }

  @Command(name = "uprebase", description = "Fetch and rebase current branch on remote, print the push command.")
  private static class Uprebase extends MGitWritableCommon implements Callable<Integer> {
    private final Logger log = LoggerFactory.getLogger(Uprebase.class);
    @Option(names = "--force-rebase", description = "Force rebase all commits (--no-ff). Rewrites all commit hashes.")
    private boolean forceRebase;
    @Option(names = "--fetch", description = "Fetch remote refs before rebase (slower)")
    boolean fetch;
    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, false);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      int rebased = 0, skipped = 0, failed = 0;

      for (File repo : repoDirs) {
        String branch = getCurrentBranch(repo);
        if (branch == null) {
          stdoutf("@|yellow [%s] skipped (detached HEAD)|@", repo.getName());
          skipped++;
          continue;
        }
        RepoStatus rs = computeRepoStatus(repo);
        if (rs.conflicted) {
          stdoutf("@|yellow [%s] skipped (unresolved conflicts)|@", repo.getName());
          skipped++;
          continue;
        }

        // === Guard 1: detect "D + ??" upfront (deleted staged + untracked dirs)
        String status = runGitCommand("status", repo, "status", "--porcelain");
        if (status.contains("D ") && status.contains("??")) {
          stdoutf("@|yellow [%s] skipped (staged deletions + untracked dirs; use 'mgit resolve --repos=%s')|@",
                  repo.getName(), repo.getName());
          skipped++;
          continue;
        }

        try {
          if (fetch) {
            try {
              doFetch(repo); // same as mgit fetch
            } catch (Exception ex) {
              log.error("Fetch failed in '{}': {}", repo.getName(), ex.getMessage());
              skipped++;
              continue;
            }
          }

          String defaultBranch = getRemoteDefaultBranch(repo);
          List<String> rebaseCmd = new ArrayList<>(List.of("rebase", "--autostash"));
          if (forceRebase) {
            rebaseCmd.add("--force-rebase");
          }
          rebaseCmd.add("origin/" + defaultBranch);

          try {
            runGitCommand("rebase", repo, rebaseCmd.toArray(new String[0]));
            String pushCmd = "git -C " + repo.getAbsolutePath() + " push --force-with-lease";
            stdoutf("@|green [%s] rebase OK. To push: %s|@", repo.getName(), pushCmd);
            rebased++;
          } catch (Exception ex) {
            String msg = ex.getMessage();
            if (msg != null && msg.contains("untracked working tree files would be overwritten by reset")) {
              // === Guard 2: detect overwrite error
              stdoutf("@|red [%s] rebase BLOCKED: untracked files would be overwritten.|@", repo.getName());
              stdoutf("@|red Run 'mgit resolve --repos=%s' to choose WORKTREE or INDEX before rebasing.|@",
                      repo.getName());
              skipped++;
            } else if (msg != null && msg.contains("rebase-merge")) {
              stdoutf("@|yellow [%s] rebase already in progress. Use 'git rebase --continue' or '--abort'.|@",
                      repo.getName());
              skipped++;
            } else {
              log.error("Rebase failed in '{}': {}", repo.getName(), msg);
              stdoutf("@|magenta [%s] rebase FAILED. Use 'mgit resolve --repos=%s' to fix conflicts|@",
                      repo.getName(), repo.getName());
              failed++;
            }
          }
        } catch (Exception ex) {
          log.error("Rebase failed in '{}': {}", repo.getName(), ex.getMessage());
          stdoutf("@|magenta [%s] rebase FAILED. Resolve manually|@", repo.getName());
          failed++;
        }
      }

      // === Final summary ===
      stdoutf("[mgit] Summary: Rebased=%d, Skipped=%d, Failed=%d", rebased, skipped, failed);
      log.info("Rebased: {}, Skipped: {}, Failed: {}", rebased, skipped, failed);

      return failed > 0 ? 1 : 0;
    }
  }

  private static String getRemoteDefaultBranch(File repo) {
    String ref = runGitCommand("defaultBranch", repo, "symbolic-ref", "refs/remotes/origin/HEAD");
    final String prefix = "refs/remotes/origin/";
    return ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref;
  }

  private static void setPrLink(File repo, String branch, String url) {
    String norm = configKeyBranch(branch);
    setPrOriginalBranchIfAbsent(repo, branch);
    runGitConfig(repo, "mgit.pr." + norm + ".link", url);
  }

  private static String getPrLink(File repo, String branch) {
    return runGitConfig(repo, "--get", "mgit.pr." + configKeyBranch(branch) + ".link", null);
  }

  private static void setPrOriginalBranchIfAbsent(File repo, String branch) {
    String norm = configKeyBranch(branch);
    String existing = runGitConfig(repo, "--get", "mgit.pr." + norm + ".branch", null);
    if (existing == null || existing.isBlank()) {
      runGitConfig(repo, "mgit.pr." + norm + ".branch", branch);
    }
  }

  private static String getPrOriginalBranch(File repo, String normalizedKey) {
    return runGitConfig(repo, "--get", "mgit.pr." + normalizedKey + ".branch", null);
  }

  private static void removePrLink(File repo, String branch) {
    runGitConfig(repo, "--unset", "mgit.pr." + configKeyBranch(branch) + ".link", null);
  }

  private static void setPrState(File repo, String branch, String state) {
    String norm = configKeyBranch(branch);
    setPrOriginalBranchIfAbsent(repo, branch);
    runGitConfig(repo, "mgit.pr." + norm + ".state", state);
  }

  private static String getPrState(File repo, String branch) {
    return runGitConfig(repo, "--get", "mgit.pr." + configKeyBranch(branch) + ".state", null);
  }

  private static void removePrState(File repo, String branch) {
    runGitConfig(repo, "--unset", "mgit.pr." + configKeyBranch(branch) + ".state", null);
  }

  private static List<String> listPrStates(File repo) {
    String out = runGitConfig(repo, "--get-regexp", "^mgit\\.pr\\..*\\.state", null);
    if (out == null || out.isBlank())
      return List.of();
    return StreamEx.of(out.split("\\r?\\n")).toList();
  }

  // Internal method to run git config
  private static String runGitConfig(File repo, String... args) {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.add("-C");
    cmd.add(repo.getAbsolutePath());
    cmd.add("config");
    cmd.add("--local");
    for (String s : args)
      if (s != null)
        cmd.add(s);
    log.debug("run git-config: {}", String.join(" ", cmd));
    try {
      org.zeroturnaround.exec.ProcessExecutor proc = new org.zeroturnaround.exec.ProcessExecutor().command(cmd).readOutput(true).exitValues(0, 1);
      // exit 1 for unset/get if key not present
      String output = proc.execute().outputUTF8().trim();
      log.debug("git-config output:\n{}", output);
      return output;
    } catch (Exception e) {
      log.debug("git-config failed: {}", e.getMessage());
      return null;
    }
  }

  private static String configKeyBranch(String branch) {
    return branch.replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "").toLowerCase();
  }

  @Command(name = "pr-created", description = "Mark PR as created for branch")
  private static class PrCreated extends MGitWritableCommon implements Callable<Integer> {
    @Option(names = "--branch", required = true, description = "Branch to mark")
    String branch;

    @Override
    public Integer call() throws Exception {
      List<File> repos = findRepos();
      for (File repo : repos) {
        setPrState(repo, branch, "created");
        System.out.printf("[%s] PR state set to CREATED for branch %s%n", repo.getName(), branch);
      }
      return 0;
    }
  }

  @Command(name = "pr-merged", description = "Mark PR as merged for branch")
  private static class PrMerged extends MGitWritableCommon implements Callable<Integer> {
    @Option(names = "--branch", required = true, description = "Branch to mark")
    String branch;

    @Override
    public Integer call() throws Exception {
      List<File> repos = findRepos();
      for (File repo : repos) {
        setPrState(repo, branch, "merged");
        // Optionally: delete PR link
        removePrLink(repo, branch);
        System.out.printf("[%s] PR state set to MERGED for branch %s%n", repo.getName(), branch);
      }
      return 0;
    }
  }

  @Command(name = "fetch", description = "Fetch all remotes for repos, and auto-merge PR state if remote branch is deleted")
  private static class Fetch extends MGitCommon implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      int fetched = 0, errors = 0;
      for (File repo : findRepos()) {
        try {
          doFetch(repo);
          fetched++;
        } catch (Exception ex) {
          log.error("Fetch failed in '{}': {}", repo.getName(), ex.getMessage());
          errors++;
        }
      }
      log.info("Fetched: {}, Errors: {}", fetched, errors);
      return errors > 0 ? 1 : 0;
    }
  }

  static void doFetch(File repo) {
    runGitCommand("fetch", repo, "fetch", "origin");
    stdoutf("@|green [%s] fetched|@", repo.getName());

    // After fetch, check PR states
    List<String> prStates = listPrStates(repo);
    for (String entry : prStates) {
      String[] parts = entry.split("\\s+", 2);
      if (parts.length < 2)
        continue;

      String key = parts[0];
      String state = parts[1];

      if (!"created".equalsIgnoreCase(state))
        continue;

      final String prefix = "mgit.pr.";
      final String suffix = ".state";
      if (!key.startsWith(prefix) || !key.endsWith(suffix))
        continue;

      String norm = key.substring(prefix.length(), key.length() - suffix.length());
      String originalBranch = getPrOriginalBranch(repo, norm);
      if (originalBranch == null || originalBranch.isBlank())
        continue;

      if (!remoteBranchExists(repo, originalBranch)) {
        setPrState(repo, originalBranch, "merged");
        System.out.printf("[%s] PR branch %s is gone from remote; state set to MERGED.%n", repo.getName(), originalBranch);
      }
    }
  }

  private static boolean remoteBranchExists(File repo, String branch) {
    // Local-only probe (no network). Requires a prior fetch/pull to be up-to-date.
    int exit = runGitExitCode("remote-tracking-present", repo, "show-ref", "--verify", "--quiet", "refs/remotes/origin/" + branch);
    return exit == 0;
  }

  enum ResolveStrategy {
    WORKTREE("trust current filesystem content (present -> add, absent -> rm)", false), INDEX("trust what was staged before the conflict", false),
    LOCAL("trust the currently checked-out branch (committed state)", false), UPSTREAM("trust the branch/commit you are applying onto", false),

    CONTINUE("rebase in progress -> continue with staged resolutions", true),
    ABORT("rebase in progress -> abort rebase and return to pre-rebase state", true),
    SKIP("rebase in progress -> skip current patch and continue", true), CLEAN("broken rebase metadata -> remove stale rebase-merge dir", true);

    final String description;
    final boolean rebaseOnly;

    ResolveStrategy(String description, boolean rebaseOnly) {
      this.description = description;
      this.rebaseOnly = rebaseOnly;
    }
  }

  static class ResolutionStep {
    final List<String> cmds;
    final String description;

    ResolutionStep(List<String> cmds, String description) {
      this.cmds = cmds;
      this.description = description;
    }
  }

  @Command(name = "resolve", description = "Resolve merge conflicts", subcommands = { Resolve.ResolveLegend.class })
  private static class Resolve extends MGitCommon implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Resolution strategy")
    ResolveStrategy strategy;

    @Option(names = "--files", split = ",", description = "Restrict to matching files (comma-separated glob patterns)")
    List<String> files;

    @Option(names = "--conflicts", split = ",", description = "Restrict to specific conflict codes (comma-separated, e.g. UD,D?,UU)")
    List<String> conflicts;

    @Option(names = "--execute", description = "Actually run git commands")
    boolean execute;

    @Override
    public Integer call() {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);

      PathMatcher fileMatcher = null;
      if (files != null && !files.isEmpty()) {
        // support comma-separated globs
        List<PathMatcher> matchers = files.stream().map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob)).toList();
        fileMatcher = path -> matchers.stream().anyMatch(m -> m.matches(path));
      }

      Set<String> conflictFilter = conflicts != null ? new HashSet<>(conflicts) : null;

      int totalConflicts = 0;
      int totalResolved = 0;
      int totalSkipped = 0;
      int totalSuggested = 0;

      for (File repo : findRepos()) {
        stdoutf("[%s] conflicts:", repo.getName());

        // --- Detect rebase in progress ---

        File gitDir = gitDir(repo);
        File rebaseMerge = new File(gitDir, "rebase-merge");
        File rebaseApply = new File(gitDir, "rebase-apply");
        log.debug("Checking for rebase in progress in {}: rebase-merge={}:{}, rebase-apply={}:{}", repo, rebaseMerge.getAbsolutePath(),
            rebaseMerge.exists(), rebaseApply.getAbsoluteFile(), rebaseApply.exists());
        if (rebaseMerge.exists() || rebaseApply.exists()) {
          String syntheticCode = "REBASE";
          Map<ResolveStrategy, List<ResolutionStep>> options = mapConflictToCommands(repo, syntheticCode, "(in-progress)");
          totalConflicts++;
          if (strategy == null) {
            stdoutf("  %s (in-progress)", syntheticCode);
            for (var entry : options.entrySet()) {
              ResolveStrategy s = entry.getKey();
              List<ResolutionStep> steps = entry.getValue();
              if (steps.isEmpty())
                continue;
              if (steps.get(0).cmds.isEmpty()) {
                stdoutf("    %s -> %s", s, steps.get(0).description);
                totalSkipped++;
              } else {
                stdoutf("    %s -> %s", s, steps.get(0).description);
                for (ResolutionStep step : steps) {
                  stdoutf("      git %s", String.join(" ", step.cmds));
                }
                totalSuggested++;
              }
            }
          } else {
            List<ResolutionStep> steps = options.getOrDefault(strategy, List.of());
            boolean ran = false;
            for (ResolutionStep step : steps) {
              if (step.cmds.isEmpty()) {
                stdoutf("   [SKIP] (in-progress) (%s)", step.description);
                totalSkipped++;
                continue;
              }
              if (!ran) {
                stdoutf("   [RESOLVE %s -> %s] (in-progress)", syntheticCode, strategy);
                ran = true;
              }
              stdoutf("     # %s", step.description);
              stdoutf("     git -C %s %s", repo.getAbsolutePath(), String.join(" ", step.cmds));
              if (execute) {
                runGitCommand("resolve", repo, step.cmds.toArray(new String[0]));
                totalResolved++;
              } else {
                totalSuggested++;
              }
            }
            if (ran && !execute) {
              stdoutf("     # (use --execute to apply this resolution)");
            }
          }
          continue; // skip normal porcelain parsing for this repo
        }

        // --- Normal porcelain status ---
        String status = runGitCommand("status", repo, "status", "--porcelain", "--branch");
        if (status.isBlank())
          continue;

        stdoutf("  # git -C %s status --branch --porcelain", repo.getAbsolutePath());

        // group codes by file
        Map<String, List<String>> codesByFile = new LinkedHashMap<>();
        for (String line : status.split("\n")) {
          if (line.isBlank() || line.startsWith("##"))
            continue;
          String code = line.substring(0, 2).trim();
          String file = line.substring(2).trim();
          // normalize ?? to ?
          if (code.equals("??"))
            code = "?";
          // skip untracked directories
          if (code.equals("?") && file.endsWith("/"))
            continue;
          codesByFile.computeIfAbsent(file, f -> new ArrayList<>()).add(code);
        }

        for (var entry : codesByFile.entrySet()) {
          String file = entry.getKey();
          List<String> codes = entry.getValue();
          String combined = String.join("", codes);

          if (fileMatcher != null && !fileMatcher.matches(Paths.get(file)))
            continue;
          if (conflictFilter != null && !conflictFilter.contains(combined))
            continue;

          Map<ResolveStrategy, List<ResolutionStep>> options = mapConflictToCommands(repo, combined, file);

          totalConflicts++;

          if (strategy == null) {
            // advisor mode
            stdoutf("  %s %s", combined, file);
            if (options.isEmpty()) {
              stdoutf("    (no resolution available for this code)");
              totalSkipped++;
            } else {
              for (var opt : options.entrySet()) {
                ResolveStrategy s = opt.getKey();
                List<ResolutionStep> steps = opt.getValue();
                if (steps.isEmpty())
                  continue;
                if (steps.get(0).cmds.isEmpty()) {
                  stdoutf("    %s -> %s", s, steps.get(0).description);
                  totalSkipped++;
                } else {
                  stdoutf("    %s -> %s", s, steps.get(0).description);
                  for (ResolutionStep step : steps) {
                    stdoutf("      git %s", String.join(" ", step.cmds));
                  }
                  totalSuggested++;
                }
              }
            }
          } else {
            // executor mode
            List<ResolutionStep> steps = options.getOrDefault(strategy, List.of());
            boolean ran = false;
            for (ResolutionStep step : steps) {
              if (step.cmds.isEmpty()) {
                stdoutf("   [SKIP] %s (%s)", file, step.description);
                totalSkipped++;
                continue;
              }
              if (!ran) {
                stdoutf("   [RESOLVE %s -> %s] %s", combined, strategy, file);
                ran = true;
              }
              stdoutf("     # %s", step.description);
              stdoutf("     git -C %s %s", repo.getAbsolutePath(), String.join(" ", step.cmds));
              if (execute) {
                runGitCommand("resolve", repo, step.cmds.toArray(new String[0]));
                totalResolved++;
              } else {
                totalSuggested++;
              }
            }
            if (ran && !execute) {
              stdoutf("     # (use --execute to apply this resolution)");
            }
          }
        }
      }

      // summary
      if (totalConflicts == 0) {
        stdoutf("[mgit] No conflicts found in selected repos");
      } else if (strategy == null) {
        stdoutf("[mgit] Summary: Conflicts=%d, Suggested=%d, Skipped=%d", totalConflicts, totalSuggested, totalSkipped);
      } else {
        stdoutf("[mgit] Summary: Conflicts=%d, Resolved=%d, Skipped=%d", totalConflicts, totalResolved, totalSkipped);
      }

      return 0;
    }

    private File gitDir(File repo) {
      try {
        File dotGit = new File(repo, ".git");
        if (dotGit.isFile()) {
          String content = Files.readString(dotGit.toPath()).trim();
          if (content.startsWith("gitdir:")) {
            content = content.substring("gitdir:".length()).trim();
          }
          Path gitPath = Paths.get(content);
          if (!gitPath.isAbsolute()) {
            // resolve relative to the repo’s root directory (same as git -C repo)
            gitPath = repo.toPath().resolve(gitPath).normalize();
          }
          return gitPath.toFile();
        } else {
          return dotGit; // normal .git directory
        }
      } catch (IOException e) {
        throw new RuntimeException("Failed to read .git dir in " + repo, e);
      }
    }

    private Map<ResolveStrategy, List<ResolutionStep>> mapConflictToCommands(File repo, String code, String file) {
      Map<ResolveStrategy, List<ResolutionStep>> result = new LinkedHashMap<>();

      // helpers
      BiConsumer<ResolveStrategy, String> skip = (s, msg) -> result.put(s, List.of(new ResolutionStep(Collections.emptyList(), "SKIP: " + msg)));
      Runnable skipAllNotConflict = () -> {
        for (ResolveStrategy s : ResolveStrategy.values()) {
          skip.accept(s, "not a conflict for mgit resolve (" + code + ")");
        }
      };

      // Guard: untracked directory lines like "?? some-dir/" are not conflicts
      if ("??".equals(code) && file.endsWith("/")) {
        for (ResolveStrategy s : ResolveStrategy.values()) {
          skip.accept(s, "untracked directory; not a conflict (" + code + ")");
        }
        return result;
      }

      switch (code) {
      // --- True unmerged conflicts (index has stages) ---
      case "UU": // modified on both sides
        result.put(ResolveStrategy.WORKTREE,
            List.of(new ResolutionStep(List.of("add", file), "file modified on both sides -> accept worktree content")));
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("checkout", "--ours", "--", file), "file modified on both sides -> keep index state"),
                new ResolutionStep(List.of("add", file), "stage index version as resolved")));
        result.put(ResolveStrategy.LOCAL,
            List.of(new ResolutionStep(List.of("checkout", "--theirs", "--", file), "file modified on both sides -> restore local side into index"),
                new ResolutionStep(List.of("add", file), "stage local side as resolved")));
        result.put(ResolveStrategy.UPSTREAM,
            List.of(new ResolutionStep(List.of("checkout", "--ours", "--", file), "file modified on both sides -> restore upstream side into index"),
                new ResolutionStep(List.of("add", file), "stage upstream side as resolved")));
        break;

      case "UD": // index records a delete, worktree still has file (semantic: delete vs keep)
        result.put(ResolveStrategy.WORKTREE,
            List.of(new ResolutionStep(List.of("add", file), "index says delete, worktree has file -> keep file (stage worktree content)")));
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("rm", "--", file), "index says delete, worktree has file -> accept staged delete")));
        break;

      case "DU": // index deleted, worktree has unmerged content (shape similar to UD)
        result.put(ResolveStrategy.WORKTREE,
            List.of(new ResolutionStep(List.of("add", file), "index deleted, worktree has content -> keep file (stage worktree content)")));
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("rm", "--", file), "index deleted, worktree has content -> accept staged delete")));
        break;

      case "AA": // added twice
        result.put(ResolveStrategy.WORKTREE,
            List.of(new ResolutionStep(List.of("add", file), "file added from both sides -> accept worktree content")));
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("checkout", "--ours", "--", file), "file added from both sides -> keep index state"),
                new ResolutionStep(List.of("add", file), "stage index version as resolved")));
        break;

      case "DD": // deleted twice
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("rm", "--", file), "file deleted from both sides -> accept staged delete")));
        break;

      case "UA":
      case "AU": // addition vs unmerged entry
        result.put(ResolveStrategy.WORKTREE,
            List.of(new ResolutionStep(List.of("add", file), "file addition vs unmerged entry -> accept worktree content")));
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("checkout", "--ours", "--", file), "file addition vs unmerged entry -> keep index state"),
                new ResolutionStep(List.of("add", file), "stage index version as resolved")));
        break;

      // --- Synthetic combined cases detected by Resolve.call() grouping ---
      case "D?": // staged delete + untracked same file in worktree
        result.put(ResolveStrategy.WORKTREE,
            List.of(new ResolutionStep(List.of("add", file), "file present in worktree, staged as delete -> keep file (stage worktree content)")));
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("rm", "--", file), "file present in worktree, staged as delete -> accept staged delete")));
        break;

      // --- Guards: not conflicts for mgit resolve (give reason and skip) ---
      case "??": // untracked file (not a conflict)
        break;
      case "D": // deleted in index, still exists in worktree
        result.put(ResolveStrategy.WORKTREE,
            List.of(new ResolutionStep(List.of("add", file), "index says deleted, but worktree has content -> keep worktree version")));
        result.put(ResolveStrategy.INDEX,
            List.of(new ResolutionStep(List.of("rm", "--", file), "index says deleted, worktree has content -> accept staged delete")));
        break;

      case "M":  // modified in worktree only
        break;

      case "A":  // staged add only
        break;

      case "R":  // rename in index only
        break;

      case "C":  // copy in index only
        break;

      case "T":  // typechange only
        break;
      case "!!": // ignored path
        for (ResolveStrategy s : ResolveStrategy.values()) {
          skip.accept(s, "ignored by .gitignore; not a conflict (" + code + ")");
        }
        break;
      case "REBASE":
        File gitDir = gitDir(repo);
        File rebaseMerge = new File(gitDir, "rebase-merge");
        File rebaseApply = new File(gitDir, "rebase-apply");

        if ((rebaseMerge.exists() || rebaseApply.exists()) && !new File(rebaseMerge, "head-name").exists()) {
          result.put(ResolveStrategy.CLEAN, List.of(
              new ResolutionStep(List.of("!rm", "-rf", rebaseMerge.getAbsolutePath()), "broken rebase metadata -> remove stale rebase-merge dir")));
        } else {
          result.put(ResolveStrategy.CONTINUE,
              List.of(new ResolutionStep(List.of("rebase", "--continue"), "rebase in progress -> continue with staged resolutions")));
          result.put(ResolveStrategy.ABORT,
              List.of(new ResolutionStep(List.of("rebase", "--abort"), "rebase in progress -> abort rebase and return to pre-rebase state")));
          result.put(ResolveStrategy.SKIP,
              List.of(new ResolutionStep(List.of("rebase", "--skip"), "rebase in progress -> skip current patch and continue")));
        }
        break;

      default:
        break;
      }

      return result;
    }

    // Legend subcommand
    @Command(name = "legend", description = "Show conflict code and strategy mapping")
    public static class ResolveLegend implements Callable<Integer> {
      @Override
      public Integer call() {
        System.out.println("Conflict strategies:");
        System.out.println("  WORKTREE   trust current filesystem content (present -> add, absent -> rm)");
        System.out.println("  INDEX      trust staged state in index");
        System.out.println("  LOCAL      restore one side of index (only valid for UU)");
        System.out.println("  UPSTREAM   restore the other side of index (only valid for UU)");
        System.out.println();
        System.out.println("Conflict codes and valid strategies:");
        System.out.println("  UU   file modified on both sides (index unmerged)");
        System.out.println("       valid: WORKTREE, INDEX, LOCAL, UPSTREAM");
        System.out.println("  UD   index says deleted, worktree has file");
        System.out.println("       valid: WORKTREE, INDEX");
        System.out.println("  DU   index deleted, worktree has unmerged content");
        System.out.println("       valid: WORKTREE, INDEX");
        System.out.println("  AA   file added twice");
        System.out.println("       valid: WORKTREE, INDEX");
        System.out.println("  DD   file deleted twice");
        System.out.println("       valid: INDEX");
        System.out.println("  UA   file addition vs unmerged entry");
        System.out.println("       valid: WORKTREE, INDEX");
        System.out.println("  AU   file addition vs unmerged entry");
        System.out.println("       valid: WORKTREE, INDEX");
        return 0;
      }
    }
  }
}
