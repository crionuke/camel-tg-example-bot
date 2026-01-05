# camel-tg-example-bot

A Telegram bot example built with [Apache Camel](https://camel.apache.org/) and [Quarkus](https://quarkus.io/), demonstrating payment integration capabilities.

## Features

- Telegram bot message handling via Apache Camel
- Payment processing with multiple methods:
  - Credit Card
  - Invoice Link
  - Telegram Stars
- Concurrent message processing with SEDA

## Prerequisites

- Java 21+
- Maven 3.9+
- Telegram Bot Token (from [@BotFather](https://t.me/BotFather))
- Telegram Payment Provider Token (for payment features)

## Configuration

Create a `.env` file based on `.env.example`:

```
TG_AUTH_TOKEN=your_bot_token
TG_PAYMENT_TOKEN=your_payment_provider_token
```

## Running the application

### Development mode

```bash
./mvnw quarkus:dev
```

### Production mode

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

### Native executable

```bash
./mvnw package -Pnative
./target/camel-tg-example-bot-1.0.0-SNAPSHOT-runner
```

## Usage

1. Start the bot
2. Send `/start` to your bot in Telegram
3. Select a payment method from the inline keyboard

## Code Formatting

This project uses [Spotless](https://github.com/diffplug/spotless) with Palantir Java Format. Code is automatically formatted during build, or run manually:

```bash
./mvnw spotless:apply
```