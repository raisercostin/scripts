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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
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

  @Command(name = "mgit", mixinStandardHelpOptions = true, version = "mgit 0.2", description = description, subcommands = { MgitCheckout.class,
      Status.class, Commit.class, Push.class, Uprebase.class, PrCreated.class, PrMerged.class }, sortOptions = false)
  public static class MgitRoot extends MGitCommon implements Runnable {
    static final Logger log = LoggerFactory.getLogger(MgitRoot.class);

    @Override
    public void run() {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      new CommandLine(this).usage(System.out);
    }
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

    static final Logger log = LoggerFactory.getLogger(MgitCheckout.class);

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);

      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      int changed = 0, warned = 0;
      for (File repo : repoDirs) {
        boolean ok = checkout(repo, newBranch, from);
        if (ok)
          changed++;
        else
          warned++;
      }
      log.info("Processed repos. Changed: {}, Warnings: {}", changed, warned);
      return (changed == 0 && warned > 0) ? 1 : 0;
    }

    private boolean checkout(File repo, String localBranch, String from) {
      String effectiveFrom = DEFAULT_BRANCH.equalsIgnoreCase(from) ? getRemoteDefaultBranch(repo) : from;
      String currentBranch = getCurrentBranch(repo);
      if (currentBranch != null && currentBranch.equals(effectiveFrom) && (localBranch == null || localBranch.isBlank())) {
        log.info("[{}] Already on '{}', nothing to do.", repo.getName(), effectiveFrom);
        return true;
      }

      if (localBranch != null && !localBranch.isBlank()) {
        // Create new local branch from source
        if (localBranchExists(repo, localBranch)) {
          log.warn("[{}] Local branch '{}' already exists. Skipping.", repo.getName(), localBranch);
          return false;
        }
        // prefer remote tracking ref if present locally (optionally fetch it if --force-fetch)
        String sourceRef;
        if (ensureLocalRemoteTracking(repo, effectiveFrom)) {
          sourceRef = "origin/" + effectiveFrom;
        } else {
          // fall back to local ref/commit name (could be tag/sha/branch)
          sourceRef = effectiveFrom;
        }

        int exit = runGitExitCode("checkout-new", repo, "checkout", "-b", localBranch, sourceRef);
        if (exit == 0) {
          // set upstream to the same source when it is remote
          if (sourceRef.startsWith("origin/")) {
            runGitExitCode("set-upstream", repo, "branch", "--set-upstream-to", sourceRef, localBranch);
          }
          log.info("[{}] created branch '{}' from '{}' and set upstream", repo.getName(), localBranch, sourceRef);
          return true;
        } else {
          log.warn("[{}] Could not create branch '{}' from '{}'. Try: mgit checkout -b {} {} --repos={}", repo.getName(), localBranch, sourceRef,
              localBranch, DEFAULT_BRANCH, repo.getName());
          return false;
        }
      } else {
        // Switch semantics
        if (localBranchExists(repo, effectiveFrom)) {
          int exit = runGitExitCode("checkout-local", repo, "checkout", effectiveFrom);
          if (exit == 0) {
            log.info("[{}] switched to '{}'", repo.getName(), effectiveFrom);
            return true;
          } else {
            log.warn("[{}] Failed to switch to '{}'. Remain on '{}'", repo.getName(), effectiveFrom, currentBranch);
            return false;
          }
        }
        if (ensureLocalRemoteTracking(repo, effectiveFrom)) {
          int exit = runGitExitCode("checkout-track", repo, "checkout", "--track", "origin/" + effectiveFrom);
          if (exit == 0) {
            log.info("[{}] tracking 'origin/{}'", repo.getName(), effectiveFrom);
            return true;
          } else {
            log.warn("[{}] Unable to track remote 'origin/{}'. Remain on `{}`. Try: mgit checkout -b {} {} --repos={}", repo.getName(), currentBranch,
                effectiveFrom, effectiveFrom, DEFAULT_BRANCH, repo.getName());
            return false;
          }
        }
        // Neither local nor remote exists
        log.warn("[{}] Branch '{}' does not exist. Remain on '{}'. To create from default: mgit checkout -b {} {} --repos={}", repo.getName(),
            effectiveFrom, currentBranch, effectiveFrom, DEFAULT_BRANCH, repo.getName());
        return false;
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
      // targeted fetch: origin/<branch> -> refs/remotes/origin/<branch>
      int exit = runGitExitCode("fetch-branch", repo, "fetch", "origin", branch + ":refs/remotes/origin/" + branch);
      return exit == 0;
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
  }

  static class StatusCounts {
    public int dirty = 0;
    public int ahead = 0;
    public int behind = 0;
    public int local = 0;
    public int clean = 0;
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
        if (rs.dirty) {
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

    String getShortStatus(File repo) {
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
      int ahead;
      int behind;
      boolean onlyLocal = false;
      String dirtyFiles = "";
      int defaultAhead;
      int defaultBehind;
    }

    static StatusState getRepoState(File repo) {
      StatusState state = new StatusState();
      state.dirtyFiles = getPorcelainStatus(repo);
      state.dirty = !state.dirtyFiles.isEmpty();

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
    List<String> cmdList = new ArrayList<>();
    cmdList.add("git");
    cmdList.add("-C");
    cmdList.add(repo.getAbsolutePath());
    for (String s : cmd)
      cmdList.add(s);
    String printableCmd = String.join(" ", cmdList);
    log.debug("run {}: {}", operation, printableCmd);
    try {
      org.zeroturnaround.exec.ProcessExecutor proc = new org.zeroturnaround.exec.ProcessExecutor().command(cmdList).readOutput(true).exitValues(0, 1,
          2); // allow not-found codes for probes
      org.zeroturnaround.exec.ProcessResult result = proc.execute();
      int exit = result.getExitValue();
      String output = result.outputUTF8().trim();
      log.debug("output {} (exit {}):\n{}", operation, exit, output);
      return exit;
    } catch (Exception e) {
      throw new RuntimeException("Failed on %s: [%s] %s".formatted(operation, printableCmd, e.getMessage()), e);
    }
  }

  static String runGitCommand(String operation, File repo, String... cmd) {
    List<String> cmdList = new ArrayList<>();
    cmdList.add("git");
    cmdList.add("-C");
    cmdList.add(repo.getAbsolutePath());
    for (String s : cmd)
      cmdList.add(s);
    String printableCmd = String.join(" ", cmdList);
    log.debug("run {}: {}", operation, printableCmd);
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

  static class RepoStatus {
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
    String statusOutput = runGitCommand("status", repo, "status", "--branch", "--porcelain");
    String[] lines = statusOutput.split("\\r?\\n");
    rs.dirty = lines.length > 1;
    rs.dirtyFiles = rs.dirty ? StreamEx.of(lines).skip(1).map(l -> "  " + l.trim()).joining("\n") : "";

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

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, false);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      int rebased = 0, failed = 0;
      for (File repo : repoDirs) {
        String branch = getCurrentBranch(repo);
        if (branch == null) {
          log.warn("Repo '{}' is in detached HEAD, skipping.", repo.getName());
          continue;
        }
        try {
          runGitCommand("fetch", repo, "fetch", "origin");
          String defaultBranch = getRemoteDefaultBranch(repo);
          List<String> rebaseCmd = new ArrayList<>(List.of("rebase", "--autostash"));
          if (forceRebase) {
            rebaseCmd.add("--force-rebase");
          }
          rebaseCmd.add("origin/" + defaultBranch);
          runGitCommand("rebase", repo, rebaseCmd.toArray(new String[0]));
          String pushCmd = "git -C " + repo.getAbsolutePath() + " push --force-with-lease";
          stdoutf("@|green [%s] rebase OK. To push: %s|@", repo.getName(), pushCmd);
          rebased++;
        } catch (Exception ex) {
          log.error("Rebase failed in '{}': {}", repo.getName(), ex.getMessage());
          stdoutf("@|magenta [%s] rebase FAILED. Resolve manually|@", repo.getName());
          failed++;
        }
      }
      log.info("Rebased: {}, Failed: {}", rebased, failed);
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
    log.info("run git-config: {}", String.join(" ", cmd));
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

    final Logger log = LoggerFactory.getLogger(Fetch.class);

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(null, verbosity != null ? verbosity.length : 0, quiet, color, debug);
      List<File> repoDirs = findRepos();
      int fetched = 0, errors = 0;
      for (File repo : repoDirs) {
        try {
          runGitCommand("fetch", repo, "fetch", "origin");
          stdoutf("@|green [%s] fetched|@", repo.getName());
          // After fetch, check PR states
          List<String> prStates = listPrStates(repo);
          for (String entry : prStates) {
            // entry lines come from: git config --get-regexp "^mgit\.pr\..*\.state"
            // Format: "mgit.pr.<normalized-branch>.state <value>"
            String[] parts = entry.split("\\s+", 2);
            if (parts.length < 2)
              continue;

            String key = parts[0];
            String state = parts[1];

            if (!"created".equalsIgnoreCase(state))
              continue;

            // Extract <normalized-branch> from "mgit.pr.<norm>.state"
            final String prefix = "mgit.pr.";
            final String suffix = ".state";
            if (!key.startsWith(prefix) || !key.endsWith(suffix))
              continue;

            String norm = key.substring(prefix.length(), key.length() - suffix.length());
            // Recover the original branch we stored earlier
            String originalBranch = getPrOriginalBranch(repo, norm);
            if (originalBranch == null || originalBranch.isBlank()) {
              // Cannot confidently check remote without original name
              continue;
            }

            if (!remoteBranchExists(repo, originalBranch)) {
              setPrState(repo, originalBranch, "merged");
              System.out.printf("[%s] PR branch %s is gone from remote; state set to MERGED.%n", repo.getName(), originalBranch);
            }
          }
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

  private static boolean remoteBranchExists(File repo, String branch) {
    // Local-only probe (no network). Requires a prior fetch/pull to be up-to-date.
    int exit = runGitExitCode("remote-tracking-present", repo, "show-ref", "--verify", "--quiet", "refs/remotes/origin/" + branch);
    return exit == 0;
  }
}
