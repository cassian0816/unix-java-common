package exchange.antx.common.util;

import com.google.common.base.Throwables;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;

import java.util.List;
import java.util.stream.Collectors;

public class PbUtil {

    private static final JsonFormat.Printer JSON_FORMAT_PRINTER = JsonFormat.printer()
            .preservingProtoFieldNames()
            .omittingInsignificantWhitespace()
            .includingDefaultValueFields()
            .sortingMapKeys();

    public static String printJsonQuietly(MessageOrBuilder messageOrBuilder) {
        if (messageOrBuilder == null) {
            return null;
        } else {
            try {
                return JSON_FORMAT_PRINTER.print(messageOrBuilder);
            } catch (InvalidProtocolBufferException e) {
                Throwables.throwIfUnchecked(e);
                throw new RuntimeException(e);
            }
        }
    }

    public static <T extends MessageOrBuilder> String printJsonListQuietly(List<T> messageOrBuilderList) {
        if (messageOrBuilderList == null || messageOrBuilderList.isEmpty()) {
            return null;
        } else {
            try {
                return messageOrBuilderList.stream().map(item -> {
                    try {
                        return JSON_FORMAT_PRINTER.print(item);
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.joining(", ", "[", "]"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final JsonFormat.Printer JSON_FORMAT_PRINTER_PRETTY = JsonFormat.printer()
            .preservingProtoFieldNames()
            .includingDefaultValueFields()
            .sortingMapKeys();

    public static String printJsonQuietlyPretty(MessageOrBuilder messageOrBuilder) {
        if (messageOrBuilder == null) {
            return null;
        } else {
            try {
                return JSON_FORMAT_PRINTER_PRETTY.print(messageOrBuilder);
            } catch (InvalidProtocolBufferException e) {
                Throwables.throwIfUnchecked(e);
                throw new RuntimeException(e);
            }
        }
    }

}
