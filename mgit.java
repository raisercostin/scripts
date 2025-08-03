//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.7
//DEPS org.slf4j:slf4j-api:2.0.9
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS one.util:streamex:0.8.2
//SOURCES com/namekis/utils/RichLogback.java

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;

import com.namekis.utils.RichLogback;

import one.util.streamex.StreamEx;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;

public class mgit {

  public static void main(String... args) {
    // AnsiConsole.systemInstall();
    int exitCode = new CommandLine(new MgitRoot()).execute(args);
    // AnsiConsole.systemUninstall();
    System.exit(exitCode);
  }

  public abstract static class CommonOptions {
    @Option(names = { "-v",
        "--verbose" }, description = "Increase verbosity. Specify multiple times to increase (-vvv).")
    boolean[] verbosity = new boolean[0];

    @Option(names = { "-q", "--quiet" }, description = "Suppress all output except errors.")
    boolean quiet = false;

    @Option(names = { "-c",
        "--color" }, description = "Enable colored output (default: true).", defaultValue = "true", showDefaultValue = Visibility.ALWAYS)
    public boolean color = true;
  }

  public abstract static class MGitCommon extends CommonOptions {
    static final Logger log = LoggerFactory.getLogger(MgitRoot.class);
    @Option(names = "--exclude", split = ",", description = "Comma-separated list of repo names to exclude (by folder name)")
    public List<String> exclude = new ArrayList<>();

    @Option(names = "--repos", description = "Comma-separated list of subdirectories to scan (default: all subdirs with .git)")
    public String repos;

    public List<File> findRepos() {
      log.debug("findRepos(): repos option = '{}', exclude = '{}'", repos, exclude);

      List<File> explicit;
      if (repos != null && !repos.isBlank()) {
        explicit = StreamEx.of(repos.split(",")).map(String::trim).map(File::new).toList();
        log.info("Filtering to explicit repos: {}", StreamEx.of(explicit).map(File::getName).joining(", "));
      } else {
        File cwd = new File(".");
        explicit = StreamEx.of(Objects.requireNonNull(cwd.listFiles())).toList();;
        log.info("Scanning all subdirectories in '{}'", cwd.getAbsolutePath());
      }
      List<File> filtered = explicit.stream()
          .filter(f -> f.isDirectory() && new File(f, ".git").exists())
          .filter(f -> exclude == null || exclude.isEmpty() || !exclude.contains(f.getName()))
          .toList();

      log.info("Final repo list after filtering: {}", filtered.stream().map(File::getName).toList());
      return filtered;
    }
  }

  public abstract static class MGitWritableCommon extends MGitCommon {

    @Option(names = "--dry-run", description = "Show what would be done, but don't make changes.")
    public boolean dryRun;

  }

  @Command(name = "mgit", mixinStandardHelpOptions = true, version = "mgit 0.1", description = """
    Multi-repo Git Tool - Allow to change multiple repos in the same way:  status, branch, commit messages, push, prs.
    """, subcommands = {
      CheckoutBranch.class, Status.class, Commit.class, Push.class }, sortOptions = false)
  public static class MgitRoot extends MGitCommon implements Runnable {
    static final Logger log = LoggerFactory.getLogger(MgitRoot.class);

    @Override
    public void run() {
      RichLogback.configureLogbackByVerbosity(verbosity != null ? verbosity.length : 0, quiet, color);
      System.out.println("Use a subcommand. Try: mgit status or mgit checkout -b <branch>");
    }
  }

  @Command(name = "checkout", description = "Checkout a new branch in all repos with changes")
  public static class CheckoutBranch extends MGitWritableCommon implements Callable<Integer> {
    @Option(names = "-b", required = true, description = "Branch to create")
    String branchName;

    final Logger log = LoggerFactory.getLogger(CheckoutBranch.class);

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(verbosity != null ? verbosity.length : 0, quiet, color);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      log.info("Scanning repos: {}", repoDirs);
      int changed = 0;
      for (File repo : repoDirs) {
        if (hasChanges(repo)) {
          log.info("Repo '{}' has changes, creating branch '{}'", repo.getName(), branchName);
          checkoutBranch(repo, branchName);
          changed++;
        } else {
          log.debug("Repo '{}' is clean, skipping", repo.getName());
        }
      }
      log.info("Branched {} repos.", changed);
      return 0;
    }

    boolean hasChanges(File repo) {
      try {
        String out = new ProcessExecutor().command("git", "status", "--porcelain").directory(repo).readOutput(true)
            .exitValueNormal().execute().outputUTF8().trim();
        return !out.isEmpty();
      } catch (Exception e) {
        log.error("Failed git status in {}: {}", repo, e.toString());
        return false;
      }
    }

    void checkoutBranch(File repo, String branch) {
      try {
        new ProcessExecutor().command("git", "checkout", "-b", branch).directory(repo).readOutput(true)
            .exitValueNormal().execute();
        System.out.printf("\u001B[32m[%s] checked out '%s'\u001B[0m%n", repo.getName(), branch);
      } catch (Exception e) {
        log.error("Failed to checkout branch in {}: {}", repo, e.toString());
      }
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
      RichLogback.configureLogbackByVerbosity(verbosity != null ? verbosity.length : 0, quiet, color);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      log.info("Scanning repos: {}", repoDirs);
      StatusCounts counts = new StatusCounts();
      for (File repo : repoDirs) {
        StatusState state = getRepoState(repo);
        System.out.println(getShortStatus(repo));
        updateStatusCounts(counts, state);
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
    }

    static StatusState getRepoState(File repo) {
      StatusState state = new StatusState();
      // Uncommitted changes
      try {
        state.dirtyFiles = getPorcelainStatus(repo);
        state.dirty = !state.dirtyFiles.isEmpty();
      } catch (Exception e) {
        log.error("Error checking dirty state in {}: {}", repo, e.getMessage(), e);
        throw new RuntimeException("Cannot determine dirty state");
      }
      // Ahead/behind
      try {
        String trackingOutput = new ProcessExecutor()
            .command("git", "rev-list", "--left-right", "--count", "HEAD...@{u}").directory(repo).readOutput(true)
            .exitValueNormal().execute().outputUTF8().trim();
        String[] parts = trackingOutput.split("\\s+");
        if (parts.length == 2) {
          state.ahead = Integer.parseInt(parts[0]);
          state.behind = Integer.parseInt(parts[1]);
        }
      } catch (Exception ex) {
        String msg = ex.getMessage();
        if (msg != null && (msg.contains("no upstream configured for branch") || msg.contains("no upstream branch")
            || msg.contains("fatal: no upstream"))) {
          // No upstream set: mark as only local.
          state.onlyLocal = true;
        } else {
          log.error("Error getting tracking info in {}: {}", repo, msg, ex);
          throw new RuntimeException("Tracking unavailable");
        }
      }
      return state;
    }

    // Helper: Short hash
    static String getShortHash(File repo) {
      try {
        return new ProcessExecutor().command("git", "rev-parse", "--short=8", "HEAD").directory(repo).readOutput(true)
            .exitValueNormal().execute().outputUTF8().trim();
      } catch (Exception ex) {
        log.error("Error getting short hash in {}: {}", repo, ex.getMessage(), ex);
        throw new RuntimeException("Short hash unavailable");
      }
    }

    // Helper: Branch or detached
    static String getBranchOrDetached(File repo, String shortHash) {
      try {
        String branch = new ProcessExecutor().command("git", "symbolic-ref", "--short", "HEAD").directory(repo)
            .readOutput(true).exitValueNormal().execute().outputUTF8().trim();
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

    // Helper: Tracking info (ahead/behind)
    static String getTracking(File repo) {
      try {
        String trackingOutput = new ProcessExecutor()
            .command("git", "rev-list", "--left-right", "--count", "HEAD...@{u}").directory(repo).readOutput(true)
            .exitValueNormal().execute().outputUTF8().trim();

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
        if (msg != null && (msg.contains("no upstream configured for branch") || msg.contains("no upstream branch")
            || msg.contains("fatal: no upstream"))) {
          // No upstream set, tracking remains empty.
          return "";
        }
        log.error("Error getting tracking info in {}: {}", repo, msg, ex);
        throw new RuntimeException("Tracking unavailable");
      }
    }

    // Helper: Porcelain status
    static String getPorcelainStatus(File repo) {
      try {
        return new ProcessExecutor().command("git", "status", "--porcelain").directory(repo).readOutput(true)
            .exitValueNormal().execute().outputUTF8().trim();
      } catch (Exception ex) {
        log.error("Error getting porcelain status in {}: {}", repo, ex.getMessage(), ex);
        throw new RuntimeException("Porcelain status unavailable");
      }
    }

    static void printStatusSummary(StatusCounts counts, boolean showLegend) {
      record Entry(String label, String color, int count, String description) {
      }
      List<Entry> order = List.of(
          new Entry("[DIRTY]", "red", counts.dirty,
              "Uncommitted local changes. Use 'mgit commit -am <msg>' or 'mgit stash'."),
          new Entry("[AHEAD]", "yellow", counts.ahead, "Local commits to push. Use 'mgit push'."),
          new Entry("[BEHIND]", "magenta", counts.behind,
              "Remote has commits to pull. Use 'mgit pull' or 'mgit fetch'."),
          new Entry("[LOCAL]", "cyan", counts.local,
              "Branch exists only locally (no remote). Use 'mgit push -u origin <branch>'."),
          new Entry("[CLEAN]", "green", counts.clean, "Branch is up to date with remote. No action needed."));

      for (Entry entry : order) {
        if (entry.count() == 0)
          continue;
        String legend = showLegend ? "   : " + entry.description() : "";
        System.out.println(Ansi.AUTO
            .string(String.format("@|%s %s|@ (%d repos)%s", entry.color(), entry.label(), entry.count(), legend)));
      }
    }
  }

  @Command(name = "commit", description = "Commit all dirty submodules with a message")
  public static class Commit extends MGitWritableCommon implements Callable<Integer> {
    @Option(names = { "-am", "--message" }, required = true, description = "Commit message")
    String message;

    final Logger log = LoggerFactory.getLogger(Commit.class);

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(verbosity != null ? verbosity.length : 0, quiet, color);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      log.info("Scanning repos: {}", repoDirs);
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
          System.out.printf("\u001B[32m[%s] committed\u001B[0m%n", repo.getName());
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
        String out = new ProcessExecutor().command("git", "status", "--porcelain").directory(repo).readOutput(true)
            .exitValueNormal().execute().outputUTF8().trim();
        return !out.isEmpty();
      } catch (Exception e) {
        log.error("Failed git status in {}: {}", repo, e.toString());
        return false;
      }
    }

    boolean isDetached(File repo) {
      try {
        String out = new ProcessExecutor().command("git", "symbolic-ref", "--short", "-q", "HEAD").directory(repo)
            .readOutput(true).exitValueAny().execute().outputUTF8().trim();
        return out.isEmpty();
      } catch (Exception e) {
        log.warn("Failed to check HEAD for {}: {}", repo, e.toString());
        return true;
      }
    }

    int gitCommit(File repo, String msg) {
      try {
        new ProcessExecutor().command("git", "add", "-A").directory(repo).exitValueNormal().execute();

        return new ProcessExecutor().command("git", "commit", "-am", msg).directory(repo).exitValueAny().execute()
            .getExitValue();
      } catch (Exception e) {
        log.error("Commit failed for {}: {}", repo, e.toString());
        return 1;
      }
    }
  }

  @Command(name = "push", description = "Push current branch for all submodules. If branch is not tracked, will use 'git push -u origin <branch>'.")
  public static class Push extends MGitWritableCommon implements Callable<Integer> {

    final Logger log = LoggerFactory.getLogger(Push.class);

    @Override
    public Integer call() throws Exception {
      RichLogback.configureLogbackByVerbosity(verbosity != null ? verbosity.length : 0, quiet, color);
      List<File> repoDirs = findRepos();
      if (repoDirs.isEmpty()) {
        log.warn("No repos found.");
        return 1;
      }
      log.info("Scanning repos: {}", repoDirs);

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

        List<String> cmd = hasUpstream ? List.of("git", "push") : List.of("git", "push", "-u", "origin", branch);

        try {
          int exit = new ProcessExecutor().command(cmd).directory(repo).redirectOutput(System.out)
              .redirectError(System.err).execute().getExitValue();
          if (exit == 0) {
            System.out.printf("\u001B[32m[%s] pushed (%s)%s\u001B[0m%n", repo.getName(), branch,
                hasUpstream ? "" : " [set-upstream]");
            pushed++;
          } else {
            log.error("Push failed in '{}'.", repo.getName());
            errors++;
          }
        } catch (Exception ex) {
          log.error("Push failed in '{}': {}", repo.getName(), ex.getMessage());
          errors++;
        }
      }
      log.info("Pushed: {}, Skipped: {}, Errors: {}", pushed, skipped, errors);
      return errors > 0 ? 1 : 0;
    }

    String getCurrentBranch(File repo) {
      try {
        String out = new ProcessExecutor().command("git", "symbolic-ref", "--short", "-q", "HEAD").directory(repo)
            .readOutput(true).exitValueAny().execute().outputUTF8().trim();
        return out.isEmpty() ? null : out;
      } catch (Exception e) {
        log.warn("Failed to check HEAD for {}: {}", repo, e.toString());
        return null;
      }
    }

    boolean isNothingToPush(File repo) {
      // Check if branch is ahead or dirty; if not, skip
      try {
        String trackingOutput = new ProcessExecutor()
            .command("git", "rev-list", "--left-right", "--count", "HEAD...@{u}").directory(repo).readOutput(true)
            .exitValueAny().execute().outputUTF8().trim();
        String[] parts = trackingOutput.split("\\s+");
        if (parts.length == 2) {
          int ahead = Integer.parseInt(parts[0]);
          return ahead == 0;
        }
        // If can't parse, just try push
        return false;
      } catch (Exception e) {
        // No upstream; must push (set-upstream)
        return false;
      }
    }

    boolean hasUpstream(File repo, String branch) {
      try {
        String upstream = new ProcessExecutor().command("git", "rev-parse", "--abbrev-ref", branch + "@{u}")
            .directory(repo).readOutput(true).exitValueAny().execute().outputUTF8().trim();
        return !upstream.isEmpty();
      } catch (Exception e) {
        return false;
      }
    }
  }
}