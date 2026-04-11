package io.havocflow.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Early-startup guard that prevents {@code chaos.enabled=true} from activating
 * in production-like Spring profiles.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} + 10, before the
 * {@code ApplicationContext} is refreshed, so chaos cannot slip through
 * even if the auto-configuration conditional checks are somehow bypassed.
 *
 * <p>The forbidden profile list is controlled by the property
 * {@code chaos.forbidden-profiles} (comma-separated, default:
 * {@code prod,production,live}). To intentionally run chaos against a
 * production-named profile, remove that name from the list.
 */
public class ChaosProductionGuard
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ChaosProductionGuard.class);

    private static final String DEFAULT_FORBIDDEN = "prod,production,live";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();

        boolean chaosEnabled = env.getProperty("chaos.enabled", Boolean.class, false);
        if (!chaosEnabled) {
            return;
        }

        String forbiddenCsv = env.getProperty("chaos.forbidden-profiles", DEFAULT_FORBIDDEN);
        Set<String> forbidden = parseCsv(forbiddenCsv);

        String[] activeProfiles = env.getActiveProfiles();
        for (String profile : activeProfiles) {
            if (forbidden.contains(profile.toLowerCase())) {
                throw new IllegalStateException(
                    "[HavocFlow] SAFETY VIOLATION: chaos.enabled=true is forbidden when "
                    + "active profile is '" + profile + "'. "
                    + "Remove chaos.enabled=true from your configuration, "
                    + "or adjust chaos.forbidden-profiles if this is intentional.");
            }
        }

        if (activeProfiles.length > 0) {
            log.info("[HavocFlow] Production guard passed — active profiles: {}",
                Arrays.toString(activeProfiles));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> result = new HashSet<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return result;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
