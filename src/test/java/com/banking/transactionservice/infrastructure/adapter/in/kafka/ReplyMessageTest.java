package com.banking.transactionservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.domain.model.ParticipantReply;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class ReplyMessageTest {

  private static final UUID ID = UUID.fromString("7b9d1f3a-5c7e-4a2b-8d4f-6a8c0e2b4d6f");
  private static final UUID CARD = UUID.fromString("2c4e6a8b-0d1f-4a3c-9e5b-7d9f1b3c5e7a");
  private static final UUID MAIN = UUID.fromString("3f2a9c1e-5b7d-4e8f-9a0b-1c2d3e4f5a6b");
  private static final UUID OTHER = UUID.fromString("5a5a5a5a-1111-4222-8333-444444444444");

  // As strict as the mapper of the service, which refuses unknown fields in REST requests.
  private final JsonMapper json =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void aResolvedCardIsReadTheSameWithOrWithoutTheDocumentOfItsHolder() {
    String known =
        "\"type\":\"CARD_RESOLVED\",\"transactionId\":\""
            + ID
            + "\",\"cardId\":\""
            + CARD
            + "\",\"customerId\":\""
            + UUID.randomUUID()
            + "\",\"mainAccountId\":\""
            + MAIN
            + "\",\"accountIds\":[\""
            + MAIN
            + "\",\""
            + OTHER
            + "\"]";
    ParticipantReply expected =
        new ParticipantReply.CardResolved(ID, CARD, MAIN, List.of(MAIN, OTHER));

    ParticipantReply withHolder =
        read(
            "{" + known + ",\"holderDocumentType\":\"DNI\",\"holderDocumentNumber\":\"12345678\"}");
    ParticipantReply withoutHolder = read("{" + known + "}");

    assertThat(withHolder).isEqualTo(expected);
    assertThat(withoutHolder).isEqualTo(expected);
  }

  private ParticipantReply read(String message) {
    return json.readValue(message, ReplyMessage.class).toReply();
  }
}
