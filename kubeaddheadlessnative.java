//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.yaml:snakeyaml:2.2
//DEPS info.picocli:picocli:4.7.5
//DEPS org.zeroturnaround:zt-exec:1.12

import picocli.CommandLine;
import picocli.CommandLine.Option;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(
    name = "kubeaddheadlessnative",
    mixinStandardHelpOptions = true,
    description = "Create a headless duplicate of a Kubernetes Service. Windows/cmd.exe/cmdr native (no pipes)."
)
public class kubeaddheadlessnative implements Callable<Integer> {

    @Option(names = {"-s", "--service"}, required = true, description = "Service name (required)")
    String service;

    @Option(names = {"-n", "--namespace"}, description = "Namespace (optional)")
    String namespace;

    @Option(names = {"-c", "--context"}, description = "Kube context (optional)")
    String context;

    @Option(names = {"-o", "--output"}, description = "Output file (optional, default: headless-<service>.yaml)")
    String output;

    public static void main(String... args) {
        int exit = new CommandLine(new kubeaddheadlessnative()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        // Build kubectl command
        StringBuilder svcCmd = new StringBuilder("kubectl get svc " + service + " -o yaml");
        if (namespace != null && !namespace.isEmpty()) svcCmd.append(" -n ").append(namespace);
        if (context != null && !context.isEmpty()) svcCmd.append(" --context ").append(context);

        String yaml = exec(svcCmd.toString());
        Yaml snake = new Yaml();
        Map<String, Object> doc = snake.load(yaml);

        // Metadata changes
        Map<String, Object> meta = (Map<String, Object>) doc.get("metadata");
        meta.put("name", meta.get("name") + "-headless");
        meta.remove("resourceVersion");
        meta.remove("uid");
        meta.remove("creationTimestamp");
        meta.remove("selfLink");
        meta.remove("annotations");
        meta.remove("generation");
        meta.remove("managedFields");

        // Spec changes
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        spec.put("clusterIP", "None");
        spec.remove("clusterIPs");
        spec.remove("ipFamilies");
        spec.remove("ipFamilyPolicy");
        spec.remove("internalTrafficPolicy");
        spec.remove("loadBalancerClass");
        spec.remove("sessionAffinity");

        // Remove status
        doc.remove("status");

        // Output YAML (Windows-friendly)
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        opts.setIndent(2);
        String outFile = (output != null && !output.isEmpty()) ? output : "headless-" + service + ".yaml";
        try (Writer out = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            new Yaml(opts).dump(doc, out);
        }
        System.out.println("Created headless service YAML: " + outFile);
        System.out.println("To apply: kubectl apply -f " + outFile +
            (namespace != null && !namespace.isEmpty() ? " -n " + namespace : "") +
            (context != null && !context.isEmpty() ? " --context " + context : ""));
        return 0;
    }

    String exec(String cmd) throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<String> command = Arrays.asList(cmd.split(" "));
        new ProcessExecutor().command(command)
                .redirectErrorStream(true)
                .redirectOutput(baos)
                .readOutput(true)
                .executeNoTimeout();
        return baos.toString(StandardCharsets.UTF_8);
    }
}
