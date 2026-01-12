package exchange.antx.common.util;

import com.google.common.base.Preconditions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.FieldMask;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Message;

import java.util.Objects;

/**
 * Utility methods for working with field masks.
 */
public class FieldMaskUtil2 {

    /**
     * Compares two protobuf message objects and computes a FieldMask based on the differences between
     * the two objects. This method can be used to help construct the FieldMask object required by
     * some API methods.
     *
     * <p>Example usage:
     *
     * <pre><code>
     * Foo originalFoo = client.getFoo();
     * Foo updatedFoo = originalFoo.toBuilder().setBar("new-bar").build();
     * FieldMask fieldMask = FieldMasks.compare(originalFoo, updatedFoo);
     * client.updateFoo(updatedFoo, fieldMask);
     * </code></pre>
     *
     * @param original The original protobuf message object.
     * @param modified The modified protobuf message object.
     * @return A FieldMask reflecting the changes between the original and modified objects.
     */
    public static <T extends GeneratedMessageV3> FieldMask compare(T original, T modified) {
        Preconditions.checkNotNull(original, "original is null");
        Preconditions.checkNotNull(modified, "modified is null");

        if (!original
                .getDescriptorForType()
                .getFullName()
                .equals(modified.getDescriptorForType().getFullName())) {
            throw new IllegalArgumentException(
                    String.format(
                            "Parameters of types %s and %s must be of the same type.",
                            original.getDescriptorForType().getFullName(),
                            modified.getDescriptorForType().getFullName()));
        }

        FieldMask.Builder mask = FieldMask.newBuilder();
        compare(mask, "", original, modified);
        return mask.build();
    }

    /**
     * Computes a FieldMask based on all of the fields of message that have been set.
     *
     * <p>For a message object {@code foo}, {@code FieldMasks.allSetFieldsOf(foo)} is equivalent to
     * {@code FieldMasks.compare(foo.getDefaultInstanceForType(), foo)}
     */
    public static <T extends GeneratedMessageV3> FieldMask allSetFieldsOf(T message) {
        Preconditions.checkNotNull(message, "message is null");
        return compare((T) message.getDefaultInstanceForType(), message);
    }

    private static void compare(
            FieldMask.Builder mask, String currentField, Message original, Message modified) {
        Descriptor descriptor = original.getDescriptorForType();
        for (FieldDescriptor field : descriptor.getFields()) {
            String fieldName = getFieldName(currentField, field);
            Object fieldValueOriginal = original.getField(field);
            Object fieldValueModified = modified.getField(field);
            if (field.isRepeated()) {
                if (!Objects.equals(fieldValueOriginal, fieldValueModified)) {
                    mask.addPaths(fieldName);
                }
            } else {
                // Because getField never returns null, we use hasField to distinguish null
                // from empty message when getType() == MESSAGE
                boolean hasValueChanged =
                        original.hasField(field) != modified.hasField(field)
                                || !Objects.equals(fieldValueOriginal, fieldValueModified);
                switch (field.getJavaType()) {
                    case MESSAGE:
                        if (hasValueChanged) {
                            if (shouldAddTopLevelMessageToPath(original, modified, field)) {
                                mask.addPaths(fieldName);
                            } else {
                                // Recursively compare to find different values
                                compare(
                                        mask, fieldName, (Message) fieldValueOriginal, (Message) fieldValueModified);
                            }
                        }
                        break;
                    case INT:
                    case LONG:
                    case FLOAT:
                    case DOUBLE:
                    case BOOLEAN:
                    case STRING:
                    case BYTE_STRING:
                    case ENUM:
                        // Handle all java types except MESSAGE
                        if (hasValueChanged) {
                            mask.addPaths(fieldName);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unexpected java type "
                                        + field.getJavaType()
                                        + " encountered for field "
                                        + fieldName);
                }
            }
        }
    }

    /**
     * Returns true if the field parameter should be added to the UpdateMask.
     */
    private static boolean shouldAddTopLevelMessageToPath(
            Message original, Message modified, FieldDescriptor field) {
        return isClearingMessage(original, modified, field)
                || isSettingEmptyOneOf(original, modified, field);
    }

    /**
     * Returns true if the original message contains an empty message field that is not present on the
     * modified message, or vice-versa, in which case the user is attempting to clear the top level
     * message field.
     */
    private static boolean isClearingMessage(
            Message original, Message modified, FieldDescriptor field) {
        Message originalValueMessage = (Message) original.getField(field);
        Message modifiedValueMessage = (Message) modified.getField(field);
        // Use getAllFields to check if there are any fields set, not whether the field exists.
        return (!modified.hasField(field)
                && original.hasField(field)
                && originalValueMessage.getAllFields().isEmpty()
                || !original.hasField(field)
                && modified.hasField(field)
                && modifiedValueMessage.getAllFields().isEmpty());
    }

    /**
     * Returns true if the modified message contains an empty oneOf message that is not present in the
     * original message. In this case, we must add the field parameter to the paths list to clear the
     * oneOf field.
     */
    private static boolean isSettingEmptyOneOf(
            Message original, Message modified, FieldDescriptor field) {
        Message fieldValueModifiedMsg = (Message) modified.getField(field);
        return !original.hasField(field)
                && modified.hasField(field)
                && field.getContainingOneof() != null
                // Checks if the message has fields, regardless of whether or not they are set.
                && fieldValueModifiedMsg.getDescriptorForType().getFields().isEmpty();
    }

    private static String getFieldName(String currentField, FieldDescriptor field) {
        if (currentField.isEmpty()) {
            return field.getName();
        } else {
            return currentField + "." + field.getName();
        }
    }
}
