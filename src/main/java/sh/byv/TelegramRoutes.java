package sh.byv;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.model.EditMessageTextMessage;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.InlineKeyboardButton;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.apache.camel.component.telegram.model.MessageResult;
import org.apache.camel.component.telegram.model.MessageResultString;
import org.apache.camel.component.telegram.model.OutgoingCallbackQueryMessage;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.apache.camel.component.telegram.model.ReplyMarkup;
import org.apache.camel.component.telegram.model.SendChatActionMessage;
import org.apache.camel.component.telegram.model.payments.AnswerPreCheckoutQueryMessage;
import org.apache.camel.component.telegram.model.payments.AnswerShippingQueryMessage;
import org.apache.camel.component.telegram.model.payments.CreateInvoiceLinkMessage;
import org.apache.camel.component.telegram.model.payments.GetMyStarBalanceMessage;
import org.apache.camel.component.telegram.model.payments.GetStarTransactionsMessage;
import org.apache.camel.component.telegram.model.payments.LabeledPrice;
import org.apache.camel.component.telegram.model.payments.MessageResultStarAmount;
import org.apache.camel.component.telegram.model.payments.MessageResultStarTransactions;
import org.apache.camel.component.telegram.model.payments.PreCheckoutQuery;
import org.apache.camel.component.telegram.model.payments.RefundStarPaymentMessage;
import org.apache.camel.component.telegram.model.payments.SendInvoiceMessage;
import org.apache.camel.component.telegram.model.payments.ShippingOption;
import org.apache.camel.component.telegram.model.payments.ShippingQuery;
import org.apache.camel.component.telegram.model.payments.StarAmount;
import org.apache.camel.component.telegram.model.payments.StarTransaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class TelegramRoutes extends RouteBuilder {

    static final String TELEGRAM_STARS = "telegram-stars";
    static final String INVOICE_LINK = "invoice-link";
    static final String VIA_PROVIDER = "via-provider";
    static final String STARS_LINK = "stars-link";
    static final String REFUND_RECENT_TX = "refund-recent-tx";
    static final String STAR_BALANCE = "star-balance";
    static final String NON_REFUNDED_TX = "non-refunded-tx";

    final ProducerTemplate producer;

    @ConfigProperty(name = "tg.payment.token")
    String paymentToken;

    @Override
    public void configure() {
        from("telegram:bots").to("seda:processing?blockWhenFull=true");
        from("direct:send").to("telegram:bots");

        from("seda:processing?concurrentConsumers=16").process(exchange -> {
            final var messageBody = exchange.getMessage().getBody();

            if (messageBody instanceof IncomingMessage incomingMessage) {
                if (incomingMessage.getSuccessfulPayment() != null) {
                    final var successfulPayment = incomingMessage.getSuccessfulPayment();
                    log.info("{}", successfulPayment);
                } else if (incomingMessage.getRefundedPayment() != null) {
                    final var refundedPayment = incomingMessage.getRefundedPayment();
                    log.info("{}", refundedPayment);
                } else {
                    final var text = incomingMessage.getText();
                    final var chatId = incomingMessage.getChat().getId();

                    if (text.equals("/start")) {
                        sendChatAction(chatId, SendChatActionMessage.Action.TYPING);
                        sendMessage(
                                chatId, "Hello, " + incomingMessage.getFrom().getFirstName());
                        sendPaymentMenu(chatId);
                    }
                }
            } else if (messageBody instanceof IncomingCallbackQuery callbackQuery) {
                log.info("{}", callbackQuery);

                answerCallbackQuery(callbackQuery.getId());

                final var chatId = callbackQuery.getMessage().getChat().getId();

                switch (callbackQuery.getData()) {
                    case VIA_PROVIDER -> {
                        hidePaymentKeyboard(callbackQuery.getMessage(), "Sending invoice...");
                        sendInvoice(chatId);
                    }
                    case INVOICE_LINK -> {
                        hidePaymentKeyboard(callbackQuery.getMessage(), "Creating invoice link...");
                        final var invoiceLink = createInvoiceLink();
                        sendMessage(chatId, "Your invoice link: " + invoiceLink);
                    }
                    case TELEGRAM_STARS -> {
                        hidePaymentKeyboard(callbackQuery.getMessage(), "Sending Telegram Stars invoice...");
                        sendInvoiceInStars(chatId);
                    }
                    case STARS_LINK -> {
                        hidePaymentKeyboard(callbackQuery.getMessage(), "Creating Telegram Stars invoice link...");
                        final var invoiceLink = createStarsLink();
                        sendMessage(chatId, "Your invoice link in stars: " + invoiceLink);
                    }
                    case REFUND_RECENT_TX -> {
                        hidePaymentKeyboard(callbackQuery.getMessage(), "Processing refund...");
                        final var nonRefundedTransactions = getNonRefundedTransactions();
                        if (!nonRefundedTransactions.isEmpty()) {
                            final var lastTransaction = nonRefundedTransactions.getLast();
                            refundTransaction(lastTransaction);
                        } else {
                            sendMessage(chatId, "No transaction found to refund");
                        }
                    }
                    case STAR_BALANCE -> {
                        hidePaymentKeyboard(callbackQuery.getMessage(), "Fetching star balance...");
                        final var balance = getMyStarBalance();
                        sendMessage(chatId, "Your star balance: " + balance.getAmount() + " stars");
                    }
                    case NON_REFUNDED_TX -> {
                        hidePaymentKeyboard(callbackQuery.getMessage(), "Fetching non-refunded transactions...");
                        final var transactions = getNonRefundedTransactions();
                        if (transactions.isEmpty()) {
                            sendMessage(chatId, "No non-refunded transactions found.");
                        } else {
                            final var sb = new StringBuilder("Non-refunded transactions:\n\n");
                            for (final var tx : transactions) {
                                sb.append("ID: ").append(tx.getId()).append("\n");
                                sb.append("Amount: ").append(tx.getAmount()).append(" stars\n");
                                final var user = tx.getSource().asUser().getUser();
                                sb.append("User: ").append(user.getFirstName());
                                if (user.getLastName() != null) {
                                    sb.append(" ").append(user.getLastName());
                                }
                                if (user.getUsername() != null) {
                                    sb.append(" (@").append(user.getUsername()).append(")");
                                }
                                sb.append("\n");
                                final var dateTime = java.time.Instant.ofEpochSecond(tx.getDate())
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                                sb.append("Date: ").append(dateTime).append("\n\n");
                            }
                            sendMessage(chatId, sb.toString());
                        }
                    }
                }

            } else if (messageBody instanceof ShippingQuery shippingQuery) {
                log.info("{}", shippingQuery);
                answerShippingQuery(shippingQuery.getId());

            } else if (messageBody instanceof PreCheckoutQuery preCheckoutQuery) {
                log.info("{}", preCheckoutQuery);
                answerPreCheckoutQuery(preCheckoutQuery.getId());

            } else {
                log.error("Unsupported message, {}", messageBody.getClass().getSimpleName());
            }
        });
    }

    void sendChatAction(final String chatId, final SendChatActionMessage.Action action) {
        final var response = producer.send("direct:send", exchange -> {
            exchange.getMessage().setHeader("CamelTelegramChatId", chatId);

            final var sendChatActionMessage = new SendChatActionMessage(action);
            exchange.getMessage().setBody(sendChatActionMessage);
        });
        final var messageResult = response.getMessage().getBody(MessageResult.class);
        log.info("Result,  {}", messageResult);
    }

    void sendMessage(final String chatId, final String message) {
        final var response = producer.send("direct:send", exchange -> {
            exchange.getMessage().setHeader("CamelTelegramChatId", chatId);
            exchange.getMessage().setBody(message);
        });
        final var messageResult = response.getMessage().getBody(MessageResult.class);
        log.info("Result,  {}", messageResult);
    }

    void sendPaymentMenu(final String chatId) {
        final var keyboardBuilder = InlineKeyboardMarkup.builder();

        keyboardBuilder.addRow(List.of(InlineKeyboardButton.builder()
                .text("Pay via provider")
                .callbackData(VIA_PROVIDER)
                .build()));

        keyboardBuilder.addRow(List.of(InlineKeyboardButton.builder()
                .text("Pay via invoice link")
                .callbackData(INVOICE_LINK)
                .build()));

        keyboardBuilder.addRow(List.of(InlineKeyboardButton.builder()
                .text("Pay by Telegram Stars")
                .callbackData(TELEGRAM_STARS)
                .build()));

        keyboardBuilder.addRow(List.of(InlineKeyboardButton.builder()
                .text("Pay by Telegram Stars via invoice link")
                .callbackData(STARS_LINK)
                .build()));

        keyboardBuilder.addRow(List.of(InlineKeyboardButton.builder()
                .text("Refund recent transaction")
                .callbackData(REFUND_RECENT_TX)
                .build()));

        keyboardBuilder.addRow(List.of(InlineKeyboardButton.builder()
                .text("Get star balance")
                .callbackData(STAR_BALANCE)
                .build()));

        keyboardBuilder.addRow(List.of(InlineKeyboardButton.builder()
                .text("Get non-refunded transactions")
                .callbackData(NON_REFUNDED_TX)
                .build()));

        sendKeyboardMessage(chatId, "What would you like to do?", keyboardBuilder.build());
    }

    void hidePaymentKeyboard(final IncomingMessage message, final String newText) {
        producer.send("direct:send", exchange -> {
            exchange.getMessage()
                    .setHeader("CamelTelegramChatId", message.getChat().getId());

            final var editMessage = EditMessageTextMessage.builder()
                    .messageId(message.getMessageId().intValue())
                    .text(newText)
                    .replyMarkup(null)
                    .build();
            exchange.getMessage().setBody(editMessage);
        });
    }

    void sendKeyboardMessage(final String chatId, final String text, final ReplyMarkup replyMarkup) {
        final var response = producer.send("direct:send", exchange -> {
            exchange.getMessage().setHeader("CamelTelegramChatId", chatId);

            final var message = new OutgoingTextMessage();
            message.setText(text);
            message.setReplyMarkup(replyMarkup);
            exchange.getMessage().setBody(message);
        });

        log.info("{}", response);
    }

    void sendInvoice(final String chatId) {
        final var response = producer.send("direct:send", exchange -> {
            exchange.getMessage().setHeader("CamelTelegramChatId", chatId);

            final var orderId = UUID.randomUUID().toString();

            final var sendInvoiceMessage = new SendInvoiceMessage();
            sendInvoiceMessage.setTitle("Camel Framework");
            sendInvoiceMessage.setDescription("Camel is an Open Source integration framework");
            sendInvoiceMessage.setPayload(orderId);
            sendInvoiceMessage.setProviderToken(paymentToken);
            sendInvoiceMessage.setCurrency("RUB");
            sendInvoiceMessage.setMaxTipAmount(50000);
            sendInvoiceMessage.setSuggestedTipAmounts(List.of(1000, 5000, 10000));
            sendInvoiceMessage.setPrices(List.of(new LabeledPrice("Total", 100 * 100)));
            sendInvoiceMessage.setNeedEmail(Boolean.TRUE);
            sendInvoiceMessage.setSendEmailToProvider(Boolean.TRUE);
            sendInvoiceMessage.setFlexible(Boolean.TRUE);

            // Provider specific data field, receipt info, etc
            sendInvoiceMessage.setProviderData(null);

            log.info("{}", sendInvoiceMessage);

            exchange.getMessage().setBody(sendInvoiceMessage);
        });

        log.info("{}", response.getMessage().getBody(MessageResult.class));
    }

    void sendInvoiceInStars(final String chatId) {
        final var response = producer.send("direct:send", exchange -> {
            exchange.getMessage().setHeader("CamelTelegramChatId", chatId);

            final var orderId = UUID.randomUUID().toString();

            final var sendInvoiceMessage = new SendInvoiceMessage();
            sendInvoiceMessage.setTitle("Camel Framework");
            sendInvoiceMessage.setDescription("Camel is an Open Source integration framework");
            sendInvoiceMessage.setPayload(orderId);
            // Pass an empty string for payments in Telegram Stars
            sendInvoiceMessage.setProviderToken("");
            sendInvoiceMessage.setCurrency("XTR");
            sendInvoiceMessage.setPrices(List.of(new LabeledPrice("Total", 1)));

            log.info("{}", sendInvoiceMessage);

            exchange.getMessage().setBody(sendInvoiceMessage);
        });

        log.info("{}", response.getMessage().getBody(MessageResult.class));
    }

    String createInvoiceLink() {
        final var response = producer.send("direct:send", exchange -> {
            final var orderId = UUID.randomUUID().toString();
            final var createInvoiceLinkMessage = new CreateInvoiceLinkMessage();
            createInvoiceLinkMessage.setTitle("Camel Framework");
            createInvoiceLinkMessage.setDescription("Camel is an Open Source integration framework");
            createInvoiceLinkMessage.setPayload(orderId);
            createInvoiceLinkMessage.setProviderToken(paymentToken);
            createInvoiceLinkMessage.setCurrency("RUB");
            createInvoiceLinkMessage.setMaxTipAmount(50000);
            createInvoiceLinkMessage.setSuggestedTipAmounts(List.of(1000, 5000, 10000));
            createInvoiceLinkMessage.setPrices(List.of(new LabeledPrice("Total", 100 * 100)));
            createInvoiceLinkMessage.setNeedEmail(Boolean.TRUE);
            createInvoiceLinkMessage.setSendEmailToProvider(Boolean.TRUE);
            createInvoiceLinkMessage.setFlexible(Boolean.TRUE);

            // Provider specific data field, receipt info, etc
            createInvoiceLinkMessage.setProviderData(null);

            log.info("{}", createInvoiceLinkMessage);

            exchange.getMessage().setBody(createInvoiceLinkMessage);
        });

        final var invoiceLink =
                response.getMessage().getBody(MessageResultString.class).getResult();
        log.info("Invoice link, {}", invoiceLink);
        return invoiceLink;
    }

    String createStarsLink() {
        final var response = producer.send("direct:send", exchange -> {
            final var orderId = UUID.randomUUID().toString();
            final var createInvoiceLinkMessage = new CreateInvoiceLinkMessage();
            createInvoiceLinkMessage.setTitle("Camel Framework");
            createInvoiceLinkMessage.setDescription("Camel is an Open Source integration framework");
            createInvoiceLinkMessage.setPayload(orderId);
            // Pass an empty string for payments in Telegram Stars
            createInvoiceLinkMessage.setProviderToken("");
            createInvoiceLinkMessage.setCurrency("XTR");
            createInvoiceLinkMessage.setPrices(List.of(new LabeledPrice("Total", 1)));

            log.info("{}", createInvoiceLinkMessage);

            exchange.getMessage().setBody(createInvoiceLinkMessage);
        });

        final var invoiceLink =
                response.getMessage().getBody(MessageResultString.class).getResult();
        log.info("Invoice link, {}", invoiceLink);
        return invoiceLink;
    }

    public void answerCallbackQuery(final String queryId) {
        final var response = producer.send("direct:send", exchange -> {
            final var outgoingCallbackQueryMessage = new OutgoingCallbackQueryMessage();
            outgoingCallbackQueryMessage.setCallbackQueryId(queryId);
            log.info("Message, {}", outgoingCallbackQueryMessage);
            exchange.getMessage().setBody(outgoingCallbackQueryMessage);
        });

        log.info("{}", response.getMessage().getBody(MessageResult.class));
    }

    public void answerPreCheckoutQuery(final String queryId) {
        final var response = producer.send("direct:send", exchange -> {
            final var answerPreCheckoutQueryMessage = new AnswerPreCheckoutQueryMessage(queryId, true, null);
            log.info("Message, {}", answerPreCheckoutQueryMessage);
            exchange.getMessage().setBody(answerPreCheckoutQueryMessage);
        });

        log.info("{}", response.getMessage().getBody(MessageResult.class));
    }

    public void answerShippingQuery(final String queryId) {
        final var response = producer.send("direct:send", exchange -> {
            final var answerShippingQueryMessage = new AnswerShippingQueryMessage(
                    queryId,
                    true,
                    List.of(
                            new ShippingOption(
                                    "car",
                                    "Car",
                                    List.of(new LabeledPrice("Today", 10000), new LabeledPrice("Tomorrow", 5000))),
                            new ShippingOption(
                                    "bike",
                                    "Bike",
                                    List.of(new LabeledPrice("Today", 5000), new LabeledPrice("Tomorrow", 2500)))),
                    null);
            log.info("{}", answerShippingQueryMessage);
            exchange.getMessage().setBody(answerShippingQueryMessage);
        });

        log.info("{}", response.getMessage().getBody(MessageResult.class));
    }

    public StarAmount getMyStarBalance() {
        final var response = producer.send("direct:send", exchange -> {
            final var getMyStarBalanceMessage = new GetMyStarBalanceMessage();
            log.info("Message, {}", getMyStarBalanceMessage);
            exchange.getMessage().setBody(getMyStarBalanceMessage);
        });

        final var starAmount = response.getMessage().getBody(MessageResultStarAmount.class).getStarAmount();
        log.info("{}", starAmount);
        return starAmount;
    }

    public List<StarTransaction> getNonRefundedTransactions() {
        final var response = producer.send("direct:send", exchange -> {
            final var getStarTransactionsMessage = new GetStarTransactionsMessage();
            log.info("Message, {}", getStarTransactionsMessage);
            exchange.getMessage().setBody(getStarTransactionsMessage);
        });

        final var starTransactions =
                response.getMessage().getBody(MessageResultStarTransactions.class).getStarTransactions();

        final var refundedTxIds = starTransactions.getTransactions().stream()
                .filter(tx -> tx.getReceiver() != null && tx.getReceiver().asUser() != null)
                .map(StarTransaction::getId)
                .collect(Collectors.toSet());

        final var nonRefundedTransactions = starTransactions.getTransactions().stream()
                .filter(tx -> tx.getSource() != null && tx.getSource().asUser() != null)
                .filter(tx -> !refundedTxIds.contains(tx.getId()))
                .toList();

        log.info("{}", starTransactions);
        return nonRefundedTransactions;
    }

    public void refundTransaction(final StarTransaction starTransaction) {
        final var txId = starTransaction.getId();
        final var userId = starTransaction.getSource().asUser().getUser().getId();

        final var response = producer.send("direct:send", exchange -> {
            final var refundStarPaymentMessage = new RefundStarPaymentMessage(userId, txId);
            log.info("Message, {}", refundStarPaymentMessage);
            exchange.getMessage().setBody(refundStarPaymentMessage);
        });

        log.info("{}", response.getMessage().getBody(MessageResult.class));
    }
}
