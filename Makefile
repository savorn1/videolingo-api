.PHONY: run build test clean package

-include .env
export

run:
	@if [ -z "$$JWT_SECRET" ]; then \
		echo "JWT_SECRET is not set. Add it to a .env file or export it before running."; \
		exit 1; \
	fi
	./mvnw spring-boot:run

build:
	./mvnw clean install

package:
	./mvnw clean package

test:
	./mvnw test

clean:
	./mvnw clean
