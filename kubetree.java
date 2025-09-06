//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli-codegen:4.7.5
//DEPS org.slf4j:slf4j-api:2.0.13
//DEPS ch.qos.logback:logback-classic:1.4.14
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1
//NATIVE_OPTIONS --no-fallback -H:+ReportExceptionStackTraces  -H:-CheckToolchain

import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.*;

@CommandLine.Command(name = "kubetree", mixinStandardHelpOptions = true, description = "Scan Kubernetes services, pods, and images.")
public class kubetree implements Callable<Integer> {

    @Option(names = {"-q", "--quiet"}, description = "Quiet output (tree friendly, one line per pod/image)")
    boolean quiet;

    @Option(names = {"-n", "--name"}, description = "Filter string for service/pod/image name (default: trino)")
    String name = "";

    private static final Logger log = LoggerFactory.getLogger(kubetree.class);

    public static void main(String[] args) {
        int exitCode = new CommandLine(new kubetree()).execute(args);
        System.exit(exitCode);
    }

@Override
public Integer call() throws Exception {
    // One call, one parse
    String json = exec("kubectl get svc,pods -o json");
    ObjectMapper om = new ObjectMapper();
    JsonNode root = om.readTree(json);

    Map<String, Map<String, String>> serviceSelectors = new LinkedHashMap<>();
    Map<String, List<PodImage>> servicePods = new LinkedHashMap<>();

    for (JsonNode item : root.path("items")) {
        String kind = item.path("kind").asText();
        String svcName = item.path("metadata").path("name").asText();
        if ("Service".equals(kind) && svcName.contains(name)) {
            JsonNode sel = item.path("spec").path("selector");
            Map<String,String> selMap = new LinkedHashMap<>();
            sel.fieldNames().forEachRemaining(f -> selMap.put(f, sel.get(f).asText()));
            serviceSelectors.put(svcName, selMap);
            servicePods.put(svcName, new ArrayList<>());
        }
    }
    if (serviceSelectors.isEmpty()) {
        log.warn("No services matching filter.");
        return 1;
    }

    for (JsonNode item : root.path("items")) {
        String kind = item.path("kind").asText();
        if (!"Pod".equals(kind)) continue;
        String podName = item.path("metadata").path("name").asText();
        JsonNode labelsNode = item.path("metadata").path("labels");
        Map<String,String> labels = new LinkedHashMap<>();
        labelsNode.fieldNames().forEachRemaining(f -> labels.put(f, labelsNode.get(f).asText()));
        for (JsonNode c : item.path("spec").path("containers")) {
            String image = c.path("image").asText();
            for (var entry : serviceSelectors.entrySet()) {
                String svc = entry.getKey();
                Map<String,String> selector = entry.getValue();
                boolean matches = true;
                for (var sel : selector.entrySet()) {
                    if (!Objects.equals(labels.get(sel.getKey()), sel.getValue())) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    servicePods.get(svc).add(new PodImage(podName, image));
                }
            }
        }
    }

    if (quiet) {
        for (var entry : servicePods.entrySet()) {
            String svc = entry.getKey();
            for (PodImage pi : entry.getValue()) {
                System.out.println("/" + svc + "/..." + pi.pod + " - " + pi.image);
            }
        }
    } else {
        for (var entry : servicePods.entrySet()) {
            String svc = entry.getKey();
            System.out.println("Service: " + svc);
            for (PodImage pi : entry.getValue()) {
                System.out.println("  Pod: " + pi.pod + "  Image: " + pi.image);
            }
        }
    }
    return 0;
}

    static class PodImage {
        final String pod;
        final String image;

        PodImage(String pod, String image) {
            this.pod = pod;
            this.image = image;
        }
    }

    // Extracts "field": "value" from json string
    static String extractJsonString(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    // Extracts { ... } (simple) for selector or labels
    static String extractJsonObject(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        if (m.find()) return m.group(1);
        return "";
    }

    // Parses label selector { "foo": "bar", "baz": "qux" }
    static Map<String,String> parseLabelSelector(String s) {
        Map<String,String> map = new LinkedHashMap<>();
        if (s == null) return map;
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    // Run a command and capture output
    String exec(String cmd) throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new ProcessExecutor()
                .command(split(cmd))
                .redirectErrorStream(true)
                .readOutput(true)
                .redirectOutput(baos)
                .executeNoTimeout();
        return baos.toString(StandardCharsets.UTF_8);
    }

    // Split command for zt-exec (basic, does not handle complex quotes)
    List<String> split(String command) {
        return Arrays.asList(command.replace("\"", "").split("\\s+"));
    }
}
