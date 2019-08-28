/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.transport.commandapi;

import static io.zeebe.protocol.record.ExecuteCommandResponseEncoder.keyNullValue;
import static io.zeebe.protocol.record.ExecuteCommandResponseEncoder.partitionIdNullValue;
import static io.zeebe.protocol.record.ExecuteCommandResponseEncoder.valueHeaderLength;

import io.zeebe.broker.transport.backpressure.RequestLimiter;
import io.zeebe.engine.processor.CommandResponseWriter;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.record.ExecuteCommandResponseEncoder;
import io.zeebe.protocol.record.MessageHeaderEncoder;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.transport.ServerOutput;
import io.zeebe.transport.ServerResponse;
import io.zeebe.util.buffer.BufferWriter;
import java.util.Objects;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class CommandResponseWriterImpl implements CommandResponseWriter, BufferWriter {
  private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
  private final ExecuteCommandResponseEncoder responseEncoder = new ExecuteCommandResponseEncoder();
  private final ServerResponse response = new ServerResponse();
  private final ServerOutput output;
  private final UnsafeBuffer rejectionReason = new UnsafeBuffer(0, 0);
  private final RequestLimiter limiter;
  private int partitionId = partitionIdNullValue();
  private long key = keyNullValue();
  private BufferWriter valueWriter;
  private RecordType recordType = RecordType.NULL_VAL;
  private ValueType valueType = ValueType.NULL_VAL;
  private short intent = Intent.NULL_VAL;
  private RejectionType rejectionType = RejectionType.NULL_VAL;

  public CommandResponseWriterImpl(final ServerOutput output, RequestLimiter limiter) {
    this.output = output;
    this.limiter = limiter;
  }

  public CommandResponseWriterImpl recordType(RecordType recordType) {
    this.recordType = recordType;
    return this;
  }

  public CommandResponseWriterImpl intent(Intent intent) {
    this.intent = intent.value();
    return this;
  }

  public CommandResponseWriterImpl valueType(ValueType valueType) {
    this.valueType = valueType;
    return this;
  }

  public CommandResponseWriterImpl partitionId(final int partitionId) {
    this.partitionId = partitionId;
    return this;
  }

  public CommandResponseWriterImpl rejectionType(RejectionType rejectionType) {
    this.rejectionType = rejectionType;
    return this;
  }

  public CommandResponseWriterImpl rejectionReason(DirectBuffer rejectionReason) {
    this.rejectionReason.wrap(rejectionReason);
    return this;
  }

  public CommandResponseWriterImpl key(final long key) {
    this.key = key;
    return this;
  }

  public CommandResponseWriterImpl valueWriter(final BufferWriter writer) {
    this.valueWriter = writer;
    return this;
  }

  public boolean tryWriteResponse(int remoteStreamId, long requestId) {
    Objects.requireNonNull(valueWriter);

    try {
      limiter.onResponse(remoteStreamId, requestId);
      response.reset().remoteStreamId(remoteStreamId).requestId(requestId).writer(this);

      return output.sendResponse(response);
    } finally {
      reset();
    }
  }

  @Override
  public void write(final MutableDirectBuffer buffer, int offset) {
    // protocol header
    messageHeaderEncoder
        .wrap(buffer, offset)
        .blockLength(responseEncoder.sbeBlockLength())
        .templateId(responseEncoder.sbeTemplateId())
        .schemaId(responseEncoder.sbeSchemaId())
        .version(responseEncoder.sbeSchemaVersion());

    offset += messageHeaderEncoder.encodedLength();

    // protocol message
    responseEncoder
        .wrap(buffer, offset)
        .recordType(recordType)
        .partitionId(partitionId)
        .valueType(valueType)
        .intent(intent)
        .key(key)
        .rejectionType(rejectionType);

    offset = responseEncoder.limit();

    final int eventLength = valueWriter.getLength();
    buffer.putShort(offset, (short) eventLength, Protocol.ENDIANNESS);

    offset += valueHeaderLength();
    valueWriter.write(buffer, offset);

    offset += eventLength;

    responseEncoder.limit(offset);
    responseEncoder.putRejectionReason(rejectionReason, 0, rejectionReason.capacity());
  }

  @Override
  public int getLength() {
    return MessageHeaderEncoder.ENCODED_LENGTH
        + ExecuteCommandResponseEncoder.BLOCK_LENGTH
        + valueHeaderLength()
        + valueWriter.getLength()
        + ExecuteCommandResponseEncoder.rejectionReasonHeaderLength()
        + rejectionReason.capacity();
  }

  protected void reset() {
    partitionId = partitionIdNullValue();
    key = keyNullValue();
    valueWriter = null;
    recordType = RecordType.NULL_VAL;
    intent = Intent.NULL_VAL;
    valueType = ValueType.NULL_VAL;
    rejectionType = RejectionType.NULL_VAL;
    rejectionReason.wrap(0, 0);
  }
}
