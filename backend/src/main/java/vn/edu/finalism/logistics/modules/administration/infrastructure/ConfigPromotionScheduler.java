package vn.edu.finalism.logistics.modules.administration.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import vn.edu.finalism.logistics.modules.administration.application.ConfigVersionPromotion;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.config.promotion.enabled", havingValue = "true", matchIfMissing = true)
class ConfigPromotionScheduler {

	private static final Logger log = LoggerFactory.getLogger(ConfigPromotionScheduler.class);

	private final ConfigVersionPromotion promotion;

	ConfigPromotionScheduler(ConfigVersionPromotion promotion) {
		this.promotion = promotion;
	}

	@Scheduled(fixedDelayString = "${app.config.promotion.poll-interval-ms:15000}")
	void poll() {
		try {
			int promoted = promotion.promoteDue();
			if (promoted > 0) {
				log.info("Promoted {} scheduled configuration versions", promoted);
			}
		}
		catch (RuntimeException e) {
			log.warn("Scheduled configuration promotion failed: {}", e.getClass().getSimpleName());
		}
	}
}
