//usr/bin/env jbang "$0" "$@" ; exit $?
import java.io.*;
import java.util.*;

public class tree {
    public static void main(String[] args) throws IOException {
        boolean fromStdin = false;
        boolean printFiles = false;
        String path = null;

        // Detect stdin pipe if no args or arg is '-'
        if (args.length == 0 || (args.length == 1 && args[0].equals("-"))) {
            fromStdin = true;
        } else if (args.length == 1) {
            path = args[0];
        } else if (args.length == 2 && args[1].equals("-f")) {
            if (args[0].equals("-")) {
                fromStdin = true;
            } else {
                path = args[0];
            }
            printFiles = true;
        } else {
            System.err.println("usage: tree.java <directory> [-f]");
            System.err.println("   or: ... | tree.java -");
            System.exit(1);
        }

        if (fromStdin) {
            // Read lines from stdin as pseudo-paths
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isEmpty()) lines.add(line.replace("\\", "/"));
                }
            }
            printTreeFromPaths(lines, System.out);
        } else {
            dirTree(System.out, path, printFiles);
        }
    }

    // For real directory traversal
    static void dirTree(PrintStream out, String path, boolean printFiles) throws IOException {
        out.print(scanDir(new File(path), printFiles, ""));
    }

    static String scanDir(File dir, boolean printFiles, String prefix) throws IOException {
        File[] items = dir.listFiles();
        if (items == null) return "";
        Arrays.sort(items, Comparator.comparing(File::getName));

        List<File> filtered = new ArrayList<>();
        for (File f : items) {
            if (printFiles || f.isDirectory()) filtered.add(f);
        }

        StringBuilder res = new StringBuilder();
        for (int i = 0; i < filtered.size(); i++) {
            File currFile = filtered.get(i);
            boolean isLast = (i == filtered.size() - 1);
            res.append(formatOutput(currFile.getName(), prefix, isLast, !currFile.isDirectory(), currFile.length()));
            if (currFile.isDirectory()) {
                String childPrefix = isLast ? prefix + "    " : prefix + "|   ";
                res.append(scanDir(currFile, printFiles, childPrefix));
            }
        }
        return res.toString();
    }

    // For pseudo-paths from stdin
    static void printTreeFromPaths(List<String> paths, PrintStream out) {
        // Build a tree structure
        Node root = new Node("");
        for (String path : paths) {
            String[] parts = path.replaceAll("^/+", "").split("/");
            Node curr = root;
            for (String part : parts) {
                curr = curr.children.computeIfAbsent(part, Node::new);
            }
        }
        // Print tree
        printNode(root, "", true, out);
    }

    static void printNode(Node node, String prefix, boolean isLast, PrintStream out) {
        if (!node.name.isEmpty()) {
            String glyph = isLast ? "----" : "|---";
            out.println(prefix + glyph + node.name);
            prefix = isLast ? prefix + "    " : prefix + "|   ";
        }
        List<Node> kids = new ArrayList<>(node.children.values());
        for (int i = 0; i < kids.size(); i++) {
            printNode(kids.get(i), prefix, i == kids.size() - 1, out);
        }
    }

    static String formatOutput(String name, String prefix, boolean isLast, boolean isFile, long size) {
        String glyph = isLast ? "----" : "|---";
        if (isFile) {
            String sz = (size == 0) ? "empty" : size + "b";
            name += " (" + sz + ")";
        }
        return prefix + glyph + name + "\n";
    }

    // Tree node for pseudo-paths
    static class Node {
        String name;
        LinkedHashMap<String, Node> children = new LinkedHashMap<>();
        Node(String name) { this.name = name; }
    }
}
