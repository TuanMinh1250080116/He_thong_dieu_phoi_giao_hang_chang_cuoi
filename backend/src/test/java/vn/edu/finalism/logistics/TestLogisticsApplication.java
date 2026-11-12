package vn.edu.finalism.logistics;

import org.springframework.boot.SpringApplication;

public class TestLogisticsApplication {

	public static void main(String[] args) {
		SpringApplication.from(LogisticsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
