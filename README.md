# camel-tg-example-bot

Telegram bot example with Apache Camel and Quarkus demonstrating Telegram Payments API integration.

## Prerequisites

This project requires unreleased changes from Apache Camel main branch. If not yet released, build `camel-telegram`
component manually:

```bash
git clone https://github.com/apache/camel.git
cd camel
./mvnw install -pl components/camel-telegram -am -DskipTests
```

## Configuration

Create a `.env` file:

```
TG_AUTH_TOKEN=your_bot_token
TG_PAYMENT_TOKEN=your_payment_provider_token
```

## Running

```bash
./mvnw quarkus:dev
```