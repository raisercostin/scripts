//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.fabric8:kubernetes-client:6.10.0
//DEPS info.picocli:picocli:4.7.5
//DEPS org.slf4j:slf4j-api:2.0.13
//DEPS ch.qos.logback:logback-classic:1.4.14

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.Collections;

@CommandLine.Command(
    name = "kubeaddheadless",
    mixinStandardHelpOptions = true,
    description = "Create a headless duplicate of a Kubernetes Service using Fabric8 Kubernetes Client, with logging."
)
public class kubeaddheadless implements Callable<Integer> {

    @Parameters(index = "0", description = "Service name (required).", arity = "0..1")
    String servicePositional;

    @Option(names = {"-s", "--service"}, description = "Service name (optional; can also be given as positional)")
    String serviceOption;

    @Option(names = {"-n", "--namespace"}, description = "Namespace (default: default)")
    String namespace = "default";

    @Option(names = {"-c", "--context"}, description = "Kube context (optional)")
    String context;

    private static final Logger LOG = LoggerFactory.getLogger(kubeaddheadless.class);

    public static void main(String... args) {
        int exit = new CommandLine(new kubeaddheadless()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        String service = (serviceOption != null && !serviceOption.isEmpty()) ? serviceOption : servicePositional;
        if (service == null || service.isEmpty()) {
            LOG.error("Service name is required (use positional arg or --service)");
            return 1;
        }
        try {
            // Build config (context aware)
            Config config = (context != null && !context.isEmpty())
                    ? Config.autoConfigure(context)
                    : Config.autoConfigure(null);

            try (KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build()) {
                Service orig = client.services().inNamespace(namespace).withName(service).get();
                if (orig == null) {
                    LOG.error("Service not found: {} in namespace {}", service, namespace);
                    return 2;
                }

                // Build headless Service
                String headlessName = orig.getMetadata().getName() + "-headless";
                Service headless = new ServiceBuilder(orig)
                        .editMetadata()
                            .withName(headlessName)
                            .withResourceVersion(null)
                            .withUid(null)
                            .withAnnotations(null)
                            .withManagedFields(Collections.emptyList())
                            .withFinalizers(Collections.emptyList())
                            .withOwnerReferences(Collections.emptyList())
                            .withCreationTimestamp(null)
                        .endMetadata()
                        .editSpec()
                            .withClusterIP("None")
                            .withClusterIPs()
                            .withIpFamilies()
                            .withIpFamilyPolicy(null)
                            .withInternalTrafficPolicy(null)
                            .withLoadBalancerClass(null)
                            .withSessionAffinity(null)
                        .endSpec()
                        .withStatus(null)
                        .build();

                client.services().inNamespace(namespace).resource(headless).create();
                LOG.info("Created headless service: {} in namespace {}", headlessName, namespace);
                return 0;
            }
        } catch (Exception e) {
            LOG.error("Error: {}", e.getMessage(), e);
            return 1;
        }
    }
}
