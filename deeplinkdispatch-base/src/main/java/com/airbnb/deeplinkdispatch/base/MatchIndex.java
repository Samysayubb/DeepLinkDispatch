package com.airbnb.deeplinkdispatch.base;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.deeplinkdispatch.DeepLinkEntry;
import com.airbnb.deeplinkdispatch.DeepLinkUri;
import com.airbnb.deeplinkdispatch.NodeMetadata;
import com.airbnb.deeplinkdispatch.UrlElement;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is a wrapper class around the byte array match index.
 * <p>Byte array format is:</p>
 * <hr/>
 * <table border="1">
 * <tr>
 * <td>Node's metadata flags</td>
 * <td>value length</td>
 * <td>children length</td><td>match id</td><td>value</td><td>children</td>
 * </tr>
 * <tr>
 * <td>1 byte</td><td>1 byte</td><td>4 bytes</td><td>2 bytes</td>
 * <td>9 + value length bytes</td><td>9 + value length + children length bytes</td>
 * </tr>
 * <tr>
 * <td>{@linkplain com.airbnb.deeplinkdispatch.NodeMetadata}
 * e.g. scheme, authority, path segment, transformation</td><td>length of the
 * node's (string) value, in bytes</td>
 * </tr>
 * </table>
 * <hr/>
 * <p>
 * This is implemented in Java for speed reasons. Converting this class to Kotlin made the
 * whole lookup operation multiple times slower.
 * This is most likely not a Kotlin issue but some syntactic sugar used must have crated some
 * overhead.
 * As this is very "bare metal" anyway it was "safer" to write this in Java to avoid any
 * instructions to be added that are not necessary.</p>
 */
public class MatchIndex {

  /**
   * Encoding used for serialization
   */
  @NonNull
  public static final String MATCH_INDEX_ENCODING = "ISO_8859_1";

  /**
   * Length of header elements in bytes
   */
  public static final int HEADER_NODE_METADATA_LENGTH = 1;
  public static final int HEADER_VALUE_LENGTH = 1;
  public static final int HEADER_MATCH_LENGTH = 2;
  public static final int HEADER_CHILDREN_LENGTH = 4;
  public static final int MATCH_DATA_URL_TEMPLATE_LENGTH = 2;
  public static final int MATCH_DATA_CLASS_LENGTH = 2;
  public static final int MATCH_DATA_METHOD_LENGTH = 1;

  public static final int HEADER_LENGTH = HEADER_NODE_METADATA_LENGTH + HEADER_VALUE_LENGTH
    + HEADER_MATCH_LENGTH + HEADER_CHILDREN_LENGTH;

  @NonNull
  public static final String ROOT_VALUE = "r";

  // Used to separate param and param value in compare return value (record separator)
  @NonNull
  public static final String MATCH_PARAM_DIVIDER_CHAR = String.valueOf((char) 0x1e);

  @NonNull
  private byte[] byteArray;

  public MatchIndex(@NonNull byte[] byteArray) {
    this.byteArray = byteArray;
  }

  /**
   * Match a given {@link com.airbnb.deeplinkdispatch.DeepLinkUri} (given as a List of
   * {@link UrlElement} against this searchh index.
   * Will return an instance of {@link DeepLinkEntry} if a match was found or null if there wasn't.
   *
   *
   * @param deeplinkUri The uri that should be matched
   * @param elements The {@link UrlElement} list of
   *                 the {@link DeepLinkUri} to match against. Must be
   *                 in correct order (scheme -> host -> path elements)
   * @param placeholders Placeholders (that are encoded at {name} in the Url inside the index. Used
   *                     to collect the set of placeholders and their values as
   *                     the {@link DeepLinkUri} is recursively
   *                     processed.
   * @param elementIndex The index of the element currently processed in the elements list above.
   * @param elementStartPosition The index of the start position of the current element int he
   *                             byte array search index.
   * @param parentBoundryPos The last element that is still part of the parent element. While
   *                         looking at children of a current element that is the last element of
   *                         the last child.
   * @param pathSegmentReplacements  A map of configurable path segment replacements and their
   *                                 values.
   * @return An instance of {@link DeepLinkEntry} if a match was found null if it wasn't.
   */
  public DeepLinkEntry matchUri(@NonNull DeepLinkUri deeplinkUri,
                                @NonNull List<UrlElement> elements,
                                @Nullable Map<String, String> placeholders,
                                int elementIndex,
                                int elementStartPosition,
                                int parentBoundryPos,
                                Map<byte[], byte[]> pathSegmentReplacements) {
    DeepLinkEntry match = null;
    int currentElementStartPosition = elementStartPosition;
    do {
      UrlElement urlElement = elements.get(elementIndex);
      CompareResult compareResult =
        compareValue(currentElementStartPosition, urlElement.getTypeFlag(),
          urlElement.getValue(), pathSegmentReplacements);
      if (compareResult != null) {
        Map<String, String> placeholdersOutput = placeholders;
        // If the compareResult is not empty we found a match with a placeholder. We need to save
        // that placeholder -- and the value it was placeholding for -- in a map and possibly
        // hand it down to the next level of recursion.
        if (!compareResult.getPlaceholderValue().isEmpty()) {
          // We need to have a new HashMap for every partial match to make sure that the
          // placeholders found in other partial matches do not overlap with the actual final match.
          placeholdersOutput = new HashMap<>(placeholders != null ? placeholders
            : Collections.<String, String>emptyMap());
          String[] compareParams =
            compareResult.getPlaceholderValue().split(MATCH_PARAM_DIVIDER_CHAR);
          // Add the found placeholder set to the map.
          placeholdersOutput.put(compareParams[0], compareParams[1]);
        }
        // Only go and try to match the next element if we have one, or if we found an empty
        // configurable path segment then we actually will go to the child element in the index
        // but use the same element again.
        if (elementIndex < elements.size() - 1
          || compareResult.isEmptyConfigurablePathSegmentMatch()) {
          // If value matched we need to explore this elements children next.
          int childrenPos = getChildrenPos(currentElementStartPosition);
          if (childrenPos != -1) {
            // Recursively call matchUri again for the next element and with the child element
            // of the current element in the index.
            // If this element match was based on an empty configurable path segment we want to
            // "skip" the match and thus use the same element or the Uri for the next round.
            match = matchUri(deeplinkUri, elements, placeholdersOutput,
              compareResult.isEmptyConfigurablePathSegmentMatch() ? elementIndex : elementIndex + 1,
              childrenPos, getElementBoundaryPos(currentElementStartPosition),
              pathSegmentReplacements);
          }
        } else {
          int matchLength = getMatchLength(currentElementStartPosition);
          if (matchLength > 0) {
            match = getDeeplinkEntryFromArray(byteArray,
              matchLength,
              getMatchDataPos(currentElementStartPosition));
            if (match != null) {
              match.setParameters(deeplinkUri,
                placeholdersOutput == null ? Collections.emptyMap() : placeholdersOutput);
            }
          }
        }
      }
      if (match != null) {
        return match;
      }
      currentElementStartPosition = getNextElementStartPosition(currentElementStartPosition,
        parentBoundryPos);
    } while (currentElementStartPosition != -1);
    return null;
  }

  @Nullable
  public static DeepLinkEntry getDeeplinkEntryFromArray(byte[] byteArray,
                                                        int matchLength,
                                                        int matchStartPosition) {
    if (matchLength == 0) {
      return null;
    }
    int position = matchStartPosition;
    int urlTemplateLength = readTwoBytesAsInt(byteArray, position);
    position += MATCH_DATA_URL_TEMPLATE_LENGTH;
    String urlTemplate = getStringFromByteArray(byteArray, position, urlTemplateLength);
    position += urlTemplateLength;
    int classLength = readTwoBytesAsInt(byteArray, position);
    position += MATCH_DATA_CLASS_LENGTH;
    String className = getStringFromByteArray(byteArray, position, classLength);
    Class deeplinkClass = null;
    try {
      deeplinkClass = Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
        "Deeplink class " + className + " not found. If you are using Proguard/R8/Dexguard please "
          + "consult README.md for correct configuration.", e
      );
    }
    position += classLength;
    int methodLength = readOneByteAsInt(byteArray, position);
    String methodName = null;
    if (methodLength > 0) {
      position += MATCH_DATA_METHOD_LENGTH;
      methodName = getStringFromByteArray(byteArray, position, methodLength);
    }
    return new DeepLinkEntry(urlTemplate, deeplinkClass, methodName);
  }

  /**
   * @param elementStartPos         The start position of the element to compare
   * @param inboundUriComponentType A flag for the URI component type of the inboundValue to
   *                                compare (like scheme, host, or path segment)
   * @param inboundValue            The byte array of the inbound URI
   * @return An instance of {@link CompareResult} if the type, length and inboundValue of the
   * element staring at elementStartPos is the same as the inboundValue given in in the parameter,
   * null otherwise.
   * More details of the match are contained in {@link CompareResult}.
   */
  @Nullable
  private CompareResult compareValue(int elementStartPos,
                                     byte inboundUriComponentType, @NonNull byte[]
    inboundValue, Map<byte[], byte[]> pathSegmentReplacements) {
    // Placeholder always matches
    int valueStartPos = elementStartPos + HEADER_LENGTH;
    NodeMetadata nodeMetadata = new NodeMetadata(byteArray[elementStartPos]);

    //Opportunistically skip doing more operations on this node if we can infer it will not match
    //based on URIComponentType
    if (nodeMetadata.isComponentTypeMismatch(inboundUriComponentType)) return null;

    int valueLength = getValueLength(elementStartPos);
    boolean isValueLengthMismatch = valueLength != inboundValue.length;
    if (isValueLengthMismatch && nodeMetadata.isValueLiteralValue) {
      //Opportunistically skip this node if the comparator's lengths mismatch.
      return null;
    }

    if (nodeMetadata.isComponentParam) {
      return compareComponentParam(valueStartPos, valueLength, inboundValue);
    } else if (nodeMetadata.isConfigurablePathSegment) {
      return compareConfigurablePathSegment(inboundValue, pathSegmentReplacements, valueStartPos,
        valueLength);
    } else {
      return arrayCompare(byteArray, valueStartPos, valueLength, inboundValue);
    }
  }

  private CompareResult arrayCompare(byte[] byteArray,
                                     int startPos,
                                     int length,
                                     byte[] compareValue) {
    if (length != compareValue.length) {
      return null;
    }
    for (int i = 0; i < length; i++) {
      if (compareValue[i] != byteArray[startPos + i]) return null;
    }
    return new CompareResult("", false);
  }

  @Nullable
  private CompareResult compareConfigurablePathSegment(@NonNull byte[] inboundValue,
                                                       Map<byte[], byte[]> pathSegmentReplacements,
                                                       int valueStartPos, int valueLength) {
    byte[] replacementValue = null;
    for (Map.Entry<byte[], byte[]> pathSegmentEntry : pathSegmentReplacements.entrySet()) {
      if (arrayCompare(byteArray, valueStartPos, valueLength, pathSegmentEntry.getKey()) != null) {
        replacementValue = pathSegmentEntry.getValue();
      }
    }
    if (replacementValue == null) {
      return null;
    }
    if (replacementValue.length == 0) {
      return new CompareResult("", true);
    }
    if (arrayCompare(inboundValue, 0, inboundValue.length, replacementValue) != null) {
      return new CompareResult("", false);
    } else {
      return null;
    }
  }

  @Nullable
  private CompareResult compareComponentParam(
    int valueStartPos, int valueLength, @NonNull byte[] inboundValue
  ) {
    if ((
      //Per com.airbnb.deeplinkdispatch.DeepLinkEntryTest.testEmptyParametersNameDontMatch
      //We should not return a match if the param (aka placeholder) is empty.
      byteArray[valueStartPos] == '{' && byteArray[valueStartPos + 1] == '}'
    ) || (
      //Per com.airbnb.deeplinkdispatch.DeepLinkEntryTest.testEmptyPathPresentParams
      //We expect an empty path to not be a match
      inboundValue.length == 0
    )) {
      return null;
    }
    // i index over inboundValue array forward
    // j index over search byte arrays inboundValue element forward
    // k index over inboundValue array backward
    for (int i = 0; i < inboundValue.length; i++) {
      if (byteArray[valueStartPos + i] == '{') {
        // Until here every char in front for the placeholder matched.
        // Now let's see if all chars within the placeholder also match.
        for (int j = valueLength - 1, k = inboundValue.length - 1; j >= 0; j--, k--) {
          if (byteArray[valueStartPos + j] == '}') {
            // Text within the placeholder fully matches. Now we just need to get the placeholder
            // string and can return.
            byte[] placeholderValue = new byte[k - i + 1];
            // Size is without braces
            byte[] placeholder = new byte[(valueStartPos + j) - (valueStartPos + i) - 1];
            System.arraycopy(inboundValue, i, placeholderValue, 0, placeholderValue.length);
            System.arraycopy(byteArray, valueStartPos + i + 1, placeholder, 0,
              placeholder.length);
            return new CompareResult(
              new String(placeholder) + MATCH_PARAM_DIVIDER_CHAR
                + new String(placeholderValue), false);
          }
          if (byteArray[valueStartPos + j] != inboundValue[k]) {
            return null;
          }
        }
      }
      if (byteArray[valueStartPos + i] != inboundValue[i]) {
        return null; // Does not match
      }
    }
    return new CompareResult("", false); // Matches but is no placeholder
  }

  /**
   * Get the next entries position, or -1 if there are no further entries.
   *
   * @param elementStartPos   The start position of the current element.
   * @param parentBoundaryPos The parent elements boundry (i.e. the first elementStartPos that is
   *                          not part of the parent element anhymore)
   * @return The next entries position, or -1 if there are no further entries.
   */
  private int getNextElementStartPosition(int elementStartPos, int parentBoundaryPos) {
    int nextElementPos = getElementBoundaryPos(elementStartPos);
    if (nextElementPos == parentBoundaryPos) {
      // This was the last element
      return -1;
    } else {
      return nextElementPos;
    }
  }

  /**
   * The elements boundary is the first elementStartPos that is not part of the parent element
   * anymore.
   *
   * @param elementStartPos The start position of the current element.
   * @return The first elementStartPos that is not part of the parent element anymore.
   */
  private int getElementBoundaryPos(int elementStartPos) {
    return getMatchDataPos(elementStartPos)
      + getMatchLength(elementStartPos)
      + getChildrenLength(elementStartPos);
  }

  /**
   * Get the position of the children element of the element starting at elementStartPos.
   *
   * @param elementStartPos The start position of the element to get the children for
   * @return children pos or -1 if there are no children.
   */
  private int getChildrenPos(int elementStartPos) {
    if (getChildrenLength(elementStartPos) == 0) {
      return -1;
    } else {
      return getMatchDataPos(elementStartPos)
        + getMatchLength(elementStartPos);
    }
  }

  /**
   * The position of the match data section of the element starting at elementStartPos.
   * <p>
   * Note: The can be 0 length in which case getMatchDataPosition() and  getChildrenPos() will
   * return the same value.
   *
   * @param elementStartPos Starting position of element to process.
   * @return The position of the match data sub array for the given elementStartPos.
   */
  private int getMatchDataPos(int elementStartPos) {
    return elementStartPos
      + HEADER_LENGTH
      + getValueLength(elementStartPos);
  }

  /**
   * The length of the value element of the element starting at elementStartPos.
   *
   * @param elementStartPos Starting position of element to process
   * @return The length of the value section of this element.
   */
  private int getValueLength(int elementStartPos) {
    return readOneByteAsInt(
      byteArray, elementStartPos
        + HEADER_NODE_METADATA_LENGTH
    );
  }

  /**
   * The length of the match section of the element starting at elementStartPos.
   *
   * @param elementStartPos Starting position of element to process
   * @return The length of the match section of this element.
   */
  private int getMatchLength(int elementStartPos) {
    return readTwoBytesAsInt(
      byteArray, elementStartPos
        + HEADER_NODE_METADATA_LENGTH
        + HEADER_VALUE_LENGTH
    );
  }

  /**
   * The length of the children section of the element starting at elementStartPos.
   *
   * @param elementStartPos Starting position of element to process
   * @return The length of the children section of this element.
   */
  private int getChildrenLength(int elementStartPos) {
    return readFourBytesAsInt(
      byteArray, elementStartPos
        + HEADER_NODE_METADATA_LENGTH
        + HEADER_VALUE_LENGTH
        + HEADER_MATCH_LENGTH
    );
  }

  public int length() {
    return byteArray.length;
  }

  private static int readOneByteAsInt(byte[] byteArray, int pos) {
    return byteArray[pos] & 0xFF;
  }

  private static int readTwoBytesAsInt(byte[] byteArray, int pos) {
    return (readOneByteAsInt(byteArray, pos)) << 8
      | (readOneByteAsInt(byteArray, pos + 1));
  }

  private static int readFourBytesAsInt(byte[] byteArray, int pos) {
    return (readOneByteAsInt(byteArray, pos)) << 24
      | (readOneByteAsInt(byteArray, pos + 1)) << 16
      | (readOneByteAsInt(byteArray, pos + 2)) << 8
      | (readOneByteAsInt(byteArray, pos + 3));
  }

  @Nullable
  private static String getStringFromByteArray(byte[] byteArray, int start, int length) {
    byte[] stringByteAray = new byte[length];
    System.arraycopy(byteArray,  start, stringByteAray, 0, length);
    try {
      return new String(stringByteAray, "utf-8");
    } catch (UnsupportedEncodingException e) {
      // Cannot be reached.
    }
    return null;
  }

  /**
   * Get filename for match index.
   *
   * @param moduleName The module name the match index is for.
   * @return The filename used to store the match index.
   */
  public static @NonNull
  String getMatchIdxFileName(@NonNull String moduleName) {
    return "dld_match_" + moduleName.toLowerCase() + ".idx";
  }
}
