package com.vortex.workflow;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ModuleBoundaryTest {
    @Test void crossModuleImportsRespectOwnershipAndBusinessModulesDoNotUseKafkaClients() throws Exception {
        Set<String> business = Set.of("bagstate", "checkin", "flight", "routing", "movement");
        Pattern imports = Pattern.compile("import com\\.vortex\\.([a-z]+)\\.([a-z]+)\\.");
        try (var files = Files.walk(Path.of("src/main/java/com/vortex"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                String owner = Path.of("src/main/java/com/vortex").relativize(path).getName(0).toString();
                var matcher = imports.matcher(source);
                while (matcher.find()) {
                    if (business.contains(matcher.group(1)) && !owner.equals(matcher.group(1))) {
                        assertFalse(Set.of("persistence", "application").contains(matcher.group(2)), path + " bypasses a module API");
                    }
                }
                if (business.contains(owner)) assertFalse(source.contains("org.apache.kafka") || source.contains("org.springframework.kafka"), path + " must use the outbox boundary");
            }
        }
    }
}
