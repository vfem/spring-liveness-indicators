# Spring Liveness Indicators

## Overview

This project is a Spring Boot starter that provides liveness indicators.
It monitors the progress and provides a liveness state based on their activity.

## Features

### Kafka Liveness Indicator

- Monitors Kafka consumer progress for Spring Kafka topic listeners.
- Takes into account state of the consumer and topic and group state, is it empty or is it consumed completely.

## Requirements

- Java 17 or higher
- Spring Boot 2.4.x
- Maven 3.6.0 or higher
- Kafka

## Configuration

The library beans can be configured using the `application.properties` file. Here are some key properties:

```yaml
liveness:
  kafka:
    #Flag to enable/disable the scheduled periodic offset movement check
    #Optional: default is true
    scheduled: true
    #The period in seconds between checks
    #Optional: default is 600
    check-period-sec: 600
    #The initial delay in seconds before the first check
    #Optional: default is 600
    check-initial-delay-sec: 600
```

## Testing

To run the tests, use the following command:

```sh
mvn test
```

## Usage

The application will automatically start monitoring Kafka consumers upon startup.
You can check the liveness state by accessing the `/actuator/health/liveness` endpoint.
But Liveness Checks must be enabled explicitly in the `application.yml` file.

```yaml
management:
  health:
    livenessstate:
      enabled: true
  endpoint:
    health:
      probes:
        enabled: true
```

### Auto-Configuration evaluation

#### Kafka Liveness Indicator

- Spring Boot Actuator must be present in the classpath.
- Spring Kafka must be present in the classpath.
- Health probes must be enabled in the `application.yml` file.
- Liveness checks must be enabled in the `application.yml` file.

## Contributing

Contributions are welcome! Please fork the repository and submit a pull request.

## License

This project is licensed under the Apache 2.0 License. See the [LICENSE](LICENSE-2.0.txt) file for details.

## Release

- generate GPG key and deploy it to keyserver, e.g. ubuntu's keyserver.ubuntu.com
- get token from Maven Central https://central.sonatype.com/account
- copy it in to appropriate settings.xml, e.g. the default, server id is `central`
- prepare release with `mvn release:prepare`
- checkout newly created tag from the master branch
- deploy version to Maven Central with `mvn deploy -Prelease`
- find pushed artifacts in the https://central.sonatype.com/publishing/deployments and make a decision to release them or drop them

