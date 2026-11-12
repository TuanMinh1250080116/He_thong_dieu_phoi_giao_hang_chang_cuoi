package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.time.Duration;

public record TokenSettings(Duration accessTtl, Duration refreshTtl) {
}
