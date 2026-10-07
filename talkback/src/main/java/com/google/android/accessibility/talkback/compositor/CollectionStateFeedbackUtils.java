/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.android.accessibility.talkback.compositor;

import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.getCollectionIsColumnTransition;
import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.getCollectionIsRowTransition;
import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.getCollectionTableItemColumnIndex;
import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.getCollectionTableItemHeadingType;
import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.getCollectionTableItemRowIndex;
import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.hasBothCount;
import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.hasColumnCount;
import static com.google.android.accessibility.utils.monitor.CollectionStateUtils.hasRowCount;

import android.content.Context;
import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.monitor.CollectionState;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Provides the collection state description for compositor feedback. */
public final class CollectionStateFeedbackUtils {

  private static final String TAG = "CollectionStateDescriptionProvider";

  private CollectionStateFeedbackUtils() {}

  /**
   * Returns the collection role description when it is transitioned to a node inside a collection.
   *
   * @see {@link CollectionState.NAVIGATE_ENTER}, {@link CollectionState.NAVIGATE_EXIT}
   * @param collectionState contextual collection state during user interaction
   * @param context the parent context
   * @return collectionTransitionDescription the collection transition description text
   */
  public static CharSequence getCollectionTransitionDescription(
      CollectionState collectionState, Context context) {
    switch (collectionState.getCollectionTransition()) {
      case CollectionState.NAVIGATE_ENTER -> {
        if (collectionState.getCollectionRoleDescription() == null) {
          switch (collectionState.getCollectionRole()) {
            case Role.ROLE_LIST:
              return CompositorUtils.joinCharSequences(
                  getCollectionName(
                      R.string.in_list,
                      R.string.in_list_with_name,
                      collectionState.getCollectionName(),
                      context),
                  getCollectionLevel(collectionState, context),
                  getCollectionListItemCount(collectionState, context));
            case Role.ROLE_GRID:
            case Role.ROLE_STAGGERED_GRID:
              return CompositorUtils.joinCharSequences(
                  getCollectionName(
                      R.string.in_grid,
                      R.string.in_grid_with_name,
                      collectionState.getCollectionName(),
                      context),
                  getCollectionLevel(collectionState, context),
                  getCollectionGridItemCount(collectionState, context));
            case Role.ROLE_PAGER:
              // A pager with a CollectionInfo with 0 or 1 elements will never get to this point
              // because CollectionState#shouldEnter returns false beforehand. This case handles
              // a ViewPager1 which doesn't have a CollectionInfo (rowCount and columnCount do
              // not exist and are set to -1) but CollectionState#shouldEnter returns true
              // because it has >2 children. It may or may not have a name.
              if (!hasAnyCount(collectionState)) {
                return getCollectionName(
                    R.string.in_pager,
                    R.string.in_pager_with_name,
                    collectionState.getCollectionName(),
                    context);
              } else if (collectionState.getCollectionRowCount() > 1
                  && collectionState.getCollectionColumnCount() > 1) {
                return getCollectionGridPagerEnter(collectionState, context);
              } else if (isVerticalAligned(collectionState)) {
                return getCollectionVerticalPagerEnter(collectionState, context);
              } else {
                return getCollectionHorizontalPagerEnter(collectionState, context);
              }
            default:
              return "";
          }
        } else { // has getCollectionRoleDescription
          LogUtils.v(
              TAG,
              "Collection role description is %s",
              collectionState.getCollectionRoleDescription());
          CharSequence itemCountCharSequence = "";
          switch (collectionState.getCollectionRole()) {
            case Role.ROLE_LIST:
              itemCountCharSequence = getCollectionListItemCount(collectionState, context);
              break;
            case Role.ROLE_GRID:
              itemCountCharSequence = getCollectionGridItemCount(collectionState, context);
              break;
            case Role.ROLE_PAGER:
              if (hasBothCount(collectionState)) {
                itemCountCharSequence = getCollectionGridPagerEnter(collectionState, context);
              } else if (hasAnyCount(collectionState)) {
                if (isVerticalAligned(collectionState)) {
                  itemCountCharSequence = getCollectionVerticalPagerEnter(collectionState, context);
                } else {
                  itemCountCharSequence =
                      getCollectionHorizontalPagerEnter(collectionState, context);
                }
              } else {
                // has no count
                itemCountCharSequence =
                    getCollectionNameWithRoleDescriptionEnter(collectionState, context);
              }
              break;
            default: // Fall out
          }
          return CompositorUtils.joinCharSequences(
              getCollectionNameWithRoleDescriptionEnter(collectionState, context),
              getCollectionLevel(collectionState, context),
              itemCountCharSequence);
        }
      }
      case CollectionState.NAVIGATE_EXIT -> {
        if (collectionState.getCollectionRoleDescription() == null) {
          switch (collectionState.getCollectionRole()) {
            case Role.ROLE_LIST:
              return getCollectionName(
                  R.string.out_of_list,
                  R.string.out_of_list_with_name,
                  collectionState.getCollectionName(),
                  context);
            case Role.ROLE_GRID:
              return getCollectionName(
                  R.string.out_of_grid,
                  R.string.out_of_grid_with_name,
                  collectionState.getCollectionName(),
                  context);
            case Role.ROLE_PAGER:
              if (hasBothCount(collectionState)) {
                return getCollectionName(
                    R.string.out_of_grid_pager,
                    R.string.out_of_grid_pager_with_name,
                    collectionState.getCollectionName(),
                    context);
              } else if (hasAnyCount(collectionState)) {
                if (isVerticalAligned(collectionState)) {
                  return getCollectionName(
                      R.string.out_of_vertical_pager,
                      R.string.out_of_vertical_pager_with_name,
                      collectionState.getCollectionName(),
                      context);
                } else {
                  return getCollectionName(
                      R.string.out_of_horizontal_pager,
                      R.string.out_of_horizontal_pager,
                      collectionState.getCollectionName(),
                      context);
                }
              } else {
                // no count
                return getCollectionName(
                    R.string.out_of_pager,
                    R.string.out_of_pager_with_name,
                    collectionState.getCollectionName(),
                    context);
              }
            default:
              return "";
          }
        } else { // has getCollectionRoleDescription
          LogUtils.v(
              TAG,
              "Collection role description is %s",
              collectionState.getCollectionRoleDescription());
          return getCollectionNameWithRoleDescriptionExit(collectionState, context);
        }
      }
      default -> {
        return "";
      }
    }
  }

  /**
   * Returns the collection item description when the collection is transitioned.
   *
   * <p>Note: The collection item description is for {@link ROLE_GRID} and {@link ROLE_LIST}.
   */
  public static CharSequence getCollectionItemTransitionDescription(
      @Nullable AccessibilityNodeInfoCompat focusedNode,
      CollectionState collectionState,
      Context context) {
    boolean isRowTransition = getCollectionIsRowTransition(collectionState);
    boolean isColumnTransition = getCollectionIsColumnTransition(collectionState);
    if (isRowTransition || isColumnTransition) {
      List<CharSequence> joinList = new ArrayList<>();
      switch (collectionState.getCollectionRole()) {
        case Role.ROLE_GRID -> {
          int headingType = getCollectionTableItemHeadingType(collectionState);
          if (TextUtils.isEmpty(getCollectionTableItemRoleDescription(collectionState))) {
            if (headingType == CollectionState.TYPE_COLUMN) {
              joinList.add(context.getString(R.string.column_heading_template));
            } else if (headingType == CollectionState.TYPE_ROW) {
              joinList.add(context.getString(R.string.row_heading_template));
            } else if (headingType == CollectionState.TYPE_INDETERMINATE) {
              joinList.add(context.getString(R.string.heading_template));
            } else if (focusedNode != null && AccessibilityNodeInfoUtils.isHeading(focusedNode)) {
              joinList.add(context.getString(R.string.heading_template));
            }
          }

          int tableItemRowIndex = getCollectionTableItemRowIndex(collectionState);
          if (isRowTransition
              && tableItemRowIndex != -1
              && headingType != CollectionState.TYPE_ROW) {
            joinList.add(
                getCollectionTableItemRowName(collectionState, tableItemRowIndex, context));
          }

          int tableItemColumnIndex = getCollectionTableItemColumnIndex(collectionState);
          if (isColumnTransition
              && tableItemColumnIndex != -1
              && headingType != CollectionState.TYPE_COLUMN) {
            joinList.add(
                getCollectionTableItemColumnName(collectionState, tableItemColumnIndex, context));
          }
          return CompositorUtils.joinCharSequences(joinList, CompositorUtils.getSeparator(), true);
        }
        case Role.ROLE_LIST -> {
          boolean isHeading = getCollectionListItemIsHeading(collectionState);
          CharSequence headingTemplate =
              (isHeading
                      && TextUtils.isEmpty(getCollectionListItemRoleDescription(collectionState)))
                  ? context.getString(R.string.heading_template)
                  : "";
          // A node within a collection item may be a heading. If this is the case and the
          // collection item itself isn't a heading but the focused node is, we should append
          // "heading".
          if (!isHeading
              && focusedNode != null
              && AccessibilityNodeInfoUtils.isHeading(focusedNode)) {
            headingTemplate = context.getString(R.string.heading_template);
          }
          return CompositorUtils.joinCharSequences(
              headingTemplate, getCollectionListItemPositionDescription(collectionState, context));
        }
      }
    }
    return "";
  }

  /** Returns the row header of the table item located if it exists. */
  public static @Nullable CharSequence getCurrentTableItemRowHeader(
      CollectionState collectionState, Context context) {
    int tableItemRowIndex = getCollectionTableItemRowIndex(collectionState);
    if (tableItemRowIndex == -1) {
      return null;
    }

    return getCollectionTableItemRowName(collectionState, tableItemRowIndex, context);
  }

  /** Returns the column header of the table item located if it exists. */
  public static @Nullable CharSequence getCurrentTableItemColumnHeader(
      CollectionState collectionState, Context context) {
    int tableItemColumnIndex = getCollectionTableItemColumnIndex(collectionState);
    if (tableItemColumnIndex == -1) {
      return null;
    }

    return getCollectionTableItemColumnName(collectionState, tableItemColumnIndex, context);
  }

  /**
   * Formats feedback for a table item cell based on user verbosity preferences.
   *
   * @param focusedNode The focused node in the table cell
   * @param cellContent The primary text content of the cell
   * @param collectionState The current collection state
   * @param context Application context
   * @param columnHeadersMode One of TABLE_HEADERS_BEFORE, TABLE_HEADERS_AFTER, or TABLE_HEADERS_OFF
   * @param speakTableRowColumnNumbers Whether row and column coordinates are spoken
   * @param speakRoles Whether role descriptions are spoken
   * @return The formatted feedback text with pauses where appropriate
   */
  public static @Nullable AccessibilityNodeInfoCompat findCellAt(
      @Nullable AccessibilityNodeInfoCompat tableRoot, int targetRow, int targetCol) {
    if (tableRoot == null || targetRow < 0 || targetCol < 0) {
      return null;
    }
    return AccessibilityNodeInfoUtils.getMatchingDescendant(
        tableRoot,
        Filter.node(
            (node) -> {
              if (node == null) {
                return false;
              }
              CollectionItemInfoCompat itemInfo = node.getCollectionItemInfo();
              return itemInfo != null
                  && itemInfo.getRowIndex() == targetRow
                  && itemInfo.getColumnIndex() == targetCol;
            }));
  }

  /**
   * Formats feedback for a table item cell based on user verbosity preferences.
   *
   * @param focusedNode The focused node in the table cell
   * @param cellNode The table cell node (or null)
   * @param tableRoot The table root node (or null)
   * @param cellContent The primary text content of the cell
   * @param collectionState The current collection state
   * @param context Application context
   * @param columnHeadersMode One of TABLE_HEADERS_BEFORE, TABLE_HEADERS_AFTER, or TABLE_HEADERS_OFF
   * @param speakTableRowColumnNumbers Whether row and column coordinates are spoken
   * @param speakRoles Whether role descriptions are spoken
   * @param isRowTransition Whether this focus move crossed a row boundary
   * @return The formatted feedback text with pauses where appropriate
   */
  public static CharSequence getTableItemCellFeedback(
      @Nullable AccessibilityNodeInfoCompat focusedNode,
      @Nullable AccessibilityNodeInfoCompat cellNode,
      @Nullable AccessibilityNodeInfoCompat tableRoot,
      CharSequence cellContent,
      CollectionState collectionState,
      Context context,
      String columnHeadersMode,
      boolean speakTableRowColumnNumbers,
      boolean speakRoles,
      boolean isRowTransition) {
    CollectionState.TableItemState itemState =
        (collectionState != null) ? collectionState.getTableItemState() : null;

    if (cellNode == null && itemState == null) {
      return cellContent;
    }

    int rowIndex = (itemState != null) ? itemState.getRowIndex() : -1;
    int colIndex = (itemState != null) ? itemState.getColumnIndex() : -1;
    if (cellNode != null && cellNode.getCollectionItemInfo() != null) {
      if (rowIndex < 0) {
        rowIndex = cellNode.getCollectionItemInfo().getRowIndex();
      }
      if (colIndex < 0) {
        colIndex = cellNode.getCollectionItemInfo().getColumnIndex();
      }
    }

    int headingType =
        (collectionState != null)
            ? getCollectionTableItemHeadingType(collectionState)
            : CollectionState.TYPE_NONE;
    boolean isHeading =
        (headingType == CollectionState.TYPE_COLUMN)
            || (rowIndex == 0
                && (headingType != CollectionState.TYPE_ROW)
                && ((focusedNode != null && AccessibilityNodeInfoUtils.isHeading(focusedNode))
                    || (cellNode != null
                        && cellNode.getCollectionItemInfo() != null
                        && cellNode.getCollectionItemInfo().isHeading())
                    || (cellNode != null && AccessibilityNodeInfoUtils.isHeading(cellNode))
                    || (tableRoot != null
                        && tableRoot.getCollectionInfo() != null
                        && (tableRoot.getCollectionInfo().getRowCount() > 1
                            || tableRoot.getCollectionInfo().getRowCount() == -1))));

    // If this cell is a column heading itself (e.g. <th> in row 0):
    if (isHeading) {
      List<CharSequence> joinList = new ArrayList<>();
      if (speakTableRowColumnNumbers) {
        if (isRowTransition && rowIndex >= 0) {
          joinList.add(context.getString(R.string.row_index_template, rowIndex + 1));
        }
        if (colIndex >= 0) {
          joinList.add(context.getString(R.string.column_index_template, colIndex + 1));
        }
      }
      CharSequence text =
          !TextUtils.isEmpty(cellContent)
              ? cellContent
              : ((cellNode != null) ? AccessibilityNodeInfoUtils.getNodeText(cellNode) : "");
      if (!TextUtils.isEmpty(text)) {
        joinList.add(text);
      }
      if (speakRoles
          && (collectionState == null
              || TextUtils.isEmpty(getCollectionTableItemRoleDescription(collectionState)))) {
        joinList.add(context.getString(R.string.column_heading_template));
      }
      return CompositorUtils.joinCharSequences(joinList, CompositorUtils.getSeparator(), true);
    }

    // Otherwise, this is a table data cell (or row header cell).
    CharSequence colHeader = null;
    if (!GlobalVariables.TABLE_HEADERS_OFF.equals(columnHeadersMode)) {
      if (itemState != null) {
        colHeader = itemState.getColumnName();
      }
      if (TextUtils.isEmpty(colHeader)
          && collectionState != null
          && colIndex >= 0
          && collectionState.getColumnHeaders() != null) {
        colHeader = collectionState.getColumnHeaders().get(colIndex);
      }
      if (TextUtils.isEmpty(colHeader) && cellNode != null) {
        colHeader = AccessibilityNodeInfoUtils.getGridColumnTitle(cellNode);
      }
      if (TextUtils.isEmpty(colHeader) && tableRoot != null && colIndex >= 0) {
        AccessibilityNodeInfoCompat headerNode = findCellAt(tableRoot, 0, colIndex);
        if (headerNode != null) {
          colHeader = CollectionState.getHeaderText(headerNode);
          if (TextUtils.isEmpty(colHeader)) {
            colHeader = AccessibilityNodeInfoUtils.getNodeText(headerNode);
          }
          if (!TextUtils.isEmpty(colHeader)
              && collectionState != null
              && collectionState.getColumnHeaders() != null) {
            collectionState.getColumnHeaders().put(colIndex, colHeader);
          }
        }
      }
    }

    CharSequence colCoord =
        (speakTableRowColumnNumbers && colIndex >= 0)
            ? context.getString(R.string.column_index_template, colIndex + 1)
            : null;
    CharSequence rowCoord =
        (speakTableRowColumnNumbers && rowIndex >= 0 && isRowTransition)
            ? context.getString(R.string.row_index_template, rowIndex + 1)
            : null;
    CharSequence rowHeader =
        (isRowTransition
                && headingType != CollectionState.TYPE_ROW
                && itemState != null)
            ? itemState.getRowName()
            : null;

    CharSequence cellText = (cellContent == null) ? "" : cellContent.toString().trim();
    if (TextUtils.isEmpty(cellText) && cellNode != null) {
      CharSequence nodeText = AccessibilityNodeInfoUtils.getNodeText(cellNode);
      if (nodeText != null) {
        cellText = nodeText.toString().trim();
      }
    }

    if (GlobalVariables.TABLE_HEADERS_BEFORE.equals(columnHeadersMode)) {
      // Header before cell data
      List<CharSequence> prefixList = new ArrayList<>();
      if (rowCoord != null) {
        prefixList.add(rowCoord);
      }
      if (rowHeader != null && rowCoord == null) {
        prefixList.add(rowHeader);
      }
      if (!TextUtils.isEmpty(colHeader)) {
        prefixList.add(colHeader);
      }
      if (colCoord != null) {
        prefixList.add(colCoord);
      }

      CharSequence prefix =
          CompositorUtils.joinCharSequences(prefixList, CompositorUtils.getSeparator(), true);
      if (TextUtils.isEmpty(prefix)) {
        return cellText;
      }
      if (TextUtils.isEmpty(cellText)) {
        return prefix;
      }
      return ensureTerminalPunctuation(prefix) + " " + cellText;
    } else if (GlobalVariables.TABLE_HEADERS_OFF.equals(columnHeadersMode)) {
      List<CharSequence> mainList = new ArrayList<>();
      if (rowCoord != null) {
        mainList.add(rowCoord);
      }
      if (rowHeader != null && rowCoord == null) {
        mainList.add(rowHeader);
      }
      if (colCoord != null) {
        mainList.add(colCoord);
      }
      if (!TextUtils.isEmpty(cellText)) {
        mainList.add(cellText);
      }
      return CompositorUtils.joinCharSequences(mainList, CompositorUtils.getSeparator(), true);
    } else {
      // Header after cell data (default: TABLE_HEADERS_AFTER)
      List<CharSequence> mainList = new ArrayList<>();
      if (rowCoord != null) {
        mainList.add(rowCoord);
      }
      if (rowHeader != null && rowCoord == null) {
        mainList.add(rowHeader);
      }
      if (colCoord != null) {
        mainList.add(colCoord);
      }
      if (!TextUtils.isEmpty(cellText)) {
        mainList.add(cellText);
      }

      CharSequence mainPart =
          CompositorUtils.joinCharSequences(mainList, CompositorUtils.getSeparator(), true);
      if (TextUtils.isEmpty(colHeader)) {
        return mainPart;
      }
      if (TextUtils.isEmpty(mainPart)) {
        return colHeader;
      }

      // Add pause between cell data and column header via terminal punctuation
      return ensureTerminalPunctuation(mainPart) + " " + colHeader;
    }
  }

  public static CharSequence getTableItemCellFeedback(
      @Nullable AccessibilityNodeInfoCompat focusedNode,
      CharSequence cellContent,
      CollectionState collectionState,
      Context context,
      String columnHeadersMode,
      boolean speakTableRowColumnNumbers,
      boolean speakRoles) {
    AccessibilityNodeInfoCompat cellNode =
        (focusedNode != null) ? AccessibilityNodeInfoUtils.getTableCellUnderTable(focusedNode) : null;
    AccessibilityNodeInfoCompat tableRoot =
        (cellNode != null) ? AccessibilityNodeInfoUtils.getTableRoot(cellNode)
            : ((focusedNode != null) ? AccessibilityNodeInfoUtils.getTableRoot(focusedNode) : null);
    boolean isRowTransition =
        (collectionState != null) && getCollectionIsRowTransition(collectionState);
    return getTableItemCellFeedback(
        focusedNode,
        cellNode,
        tableRoot,
        cellContent,
        collectionState,
        context,
        columnHeadersMode,
        speakTableRowColumnNumbers,
        speakRoles,
        isRowTransition);
  }

  private static String ensureTerminalPunctuation(CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return "";
    }
    String trimmed = text.toString().trim();
    if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") || trimmed.endsWith(";")) {
      return trimmed;
    }
    return trimmed + ".";
  }

  private static CharSequence getCollectionListItemPositionDescription(
      CollectionState collectionState, Context context) {
    int rowCount = collectionState.getCollectionRowCount();
    int colCount = collectionState.getCollectionColumnCount();
    CollectionState.ListItemState itemState = collectionState.getListItemState();
    int itemIndex = itemState != null ? itemState.getIndex() : -1;
    // Order of row and column checks does not matter since a list should have either the row
    // or column count populated with > 1 but not both. Otherwise it's a grid.
    if (itemIndex >= 0 && colCount > 1 && rowCount != -1) {
      return context.getString(R.string.list_index_template, itemIndex + 1, colCount);
    }
    if (itemIndex >= 0 && rowCount > 1 && colCount != -1) {
      return context.getString(R.string.list_index_template, itemIndex + 1, rowCount);
    }
    return "";
  }

  private static boolean getCollectionListItemIsHeading(CollectionState collectionState) {
    CollectionState.ListItemState itemState = collectionState.getListItemState();
    return itemState != null && itemState.isHeading();
  }

  private static CharSequence getCollectionListItemRoleDescription(
      CollectionState collectionState) {
    CollectionState.ListItemState itemState = collectionState.getListItemState();
    return itemState != null ? itemState.getRoleDescription() : "";
  }

  private static CharSequence getCollectionTableItemRowName(CollectionState collectionState) {
    CollectionState.TableItemState itemState = collectionState.getTableItemState();
    return itemState != null ? itemState.getRowName() : "";
  }

  private static CharSequence getCollectionTableItemRowName(
      CollectionState collectionState, int tableItemRowIndex, Context context) {
    CharSequence tableItemRowName = getCollectionTableItemRowName(collectionState);
    if (!TextUtils.isEmpty(tableItemRowName)) {
      return tableItemRowName;
    } else {
      int newRowIndex = tableItemRowIndex + 1;
      return context.getString(R.string.row_index_template, newRowIndex);
    }
  }

  private static CharSequence getCollectionTableItemColumnName(CollectionState collectionState) {
    CollectionState.TableItemState itemState = collectionState.getTableItemState();
    return itemState != null ? itemState.getColumnName() : "";
  }

  private static CharSequence getCollectionTableItemColumnName(
      CollectionState collectionState, int tableItemColumnIndex, Context context) {
    CharSequence tableItemColumnName = getCollectionTableItemColumnName(collectionState);
    if (!TextUtils.isEmpty(tableItemColumnName)) {
      return tableItemColumnName;
    } else {
      int newColumnIndex = tableItemColumnIndex + 1;
      return context.getString(R.string.column_index_template, newColumnIndex);
    }
  }

  private static CharSequence getCollectionTableItemRoleDescription(
      CollectionState collectionState) {
    CollectionState.TableItemState itemState = collectionState.getTableItemState();
    return itemState != null ? itemState.getRoleDescription() : "";
  }

  private static CharSequence getCollectionLevel(CollectionState collectionState, Context context) {
    if (collectionState.getCollectionLevel() >= 0) {
      return context.getString(
          R.string.template_collection_level, collectionState.getCollectionLevel() + 1);
    }
    return "";
  }

  private static boolean isVerticalAligned(CollectionState collectionState) {
    return collectionState.getCollectionAlignment() == CollectionState.ALIGNMENT_VERTICAL;
  }

  private static boolean isHorizontalAligned(CollectionState collectionState) {
    return collectionState.getCollectionAlignment() == CollectionState.ALIGNMENT_HORIZONTAL;
  }

  private static CharSequence getCollectionListItemCount(
      CollectionState collectionState, Context context) {
    CharSequence totalCountWithIgnoredItems =
        quantityCharSequenceForTotalCountWithIgnoredItems(collectionState, context);
    if (!TextUtils.isEmpty(totalCountWithIgnoredItems)) {
      return totalCountWithIgnoredItems;
    }

    if (hasBothCount(collectionState)) {
      if (isVerticalAligned(collectionState) && collectionState.getCollectionRowCount() >= 0) {
        return quantityCharSequence(
            R.plurals.template_list_total_count,
            collectionState.getCollectionRowCount(),
            collectionState.getCollectionRowCount(),
            context);
      } else if (isHorizontalAligned(collectionState)
          && collectionState.getCollectionColumnCount() >= 0) {
        return quantityCharSequence(
            R.plurals.template_list_total_count,
            collectionState.getCollectionColumnCount(),
            collectionState.getCollectionColumnCount(),
            context);
      }
    }
    return "";
  }

  private static CharSequence getCollectionGridItemCount(
      CollectionState collectionState, Context context) {
    if (hasBothCount(collectionState)) {
      return CompositorUtils.joinCharSequences(
          quantityCharSequenceForTotalCountWithIgnoredItems(collectionState, context),
          quantityCharSequenceForRow(collectionState, context),
          quantityCharSequenceForColumn(collectionState, context));
    } else if (hasRowCount(collectionState)) {
      return CompositorUtils.joinCharSequences(
          quantityCharSequenceForTotalCountWithIgnoredItems(collectionState, context),
          quantityCharSequenceForRow(collectionState, context));
    } else if (hasColumnCount(collectionState)) {
      return CompositorUtils.joinCharSequences(
          quantityCharSequenceForTotalCountWithIgnoredItems(collectionState, context),
          quantityCharSequenceForColumn(collectionState, context));
    }
    return "";
  }

  private static CharSequence quantityCharSequenceForTotalCountWithIgnoredItems(
      CollectionState collectionState, Context context) {
    int itemCount = collectionState.getItemCount();
    int importantForAccessibilityItemCount =
        collectionState.getImportantForAccessibilityCollectionCount();
    if (importantForAccessibilityItemCount > 0 && itemCount > importantForAccessibilityItemCount) {
      return context
          .getResources()
          .getQuantityString(
              R.plurals.template_list_item_count,
              itemCount,
              itemCount,
              itemCount - importantForAccessibilityItemCount);
    }
    return "";
  }

  private static CharSequence quantityCharSequenceForRow(
      CollectionState collectionState, Context context) {
    return quantityCharSequence(
        R.plurals.template_list_row_count,
        collectionState.getCollectionRowCount(),
        collectionState.getCollectionRowCount(),
        context);
  }

  private static CharSequence quantityCharSequenceForColumn(
      CollectionState collectionState, Context context) {
    return quantityCharSequence(
        R.plurals.template_list_column_count,
        collectionState.getCollectionColumnCount(),
        collectionState.getCollectionColumnCount(),
        context);
  }

  private static CharSequence quantityCharSequence(
      int resourceId, int count1, int count2, Context context) {
    return context.getResources().getQuantityString(resourceId, count1, count2);
  }

  private static CharSequence getCollectionName(
      int stringResId, int withNameStringResId, CharSequence collectionName, Context context) {
    if (TextUtils.isEmpty(collectionName)) {
      return context.getString(stringResId);
    } else if (!TextUtils.isEmpty(collectionName)) {
      return context.getString(withNameStringResId, collectionName);
    }
    return "";
  }

  private static CharSequence getCollectionNameWithRoleDescriptionEnter(
      CollectionState collectionState, Context context) {
    if (TextUtils.isEmpty(collectionState.getCollectionRoleDescription())) {
      return "";
    }
    if (collectionState.getCollectionName() != null) {
      return context.getString(
          R.string.in_collection_role_description_with_name,
          collectionState.getCollectionRoleDescription(),
          collectionState.getCollectionName());
    } else {
      return context.getString(
          R.string.in_collection_role_description, collectionState.getCollectionRoleDescription());
    }
  }

  private static CharSequence getCollectionNameWithRoleDescriptionExit(
      CollectionState collectionState, Context context) {
    if (TextUtils.isEmpty(collectionState.getCollectionRoleDescription())) {
      return "";
    }
    if (collectionState.getCollectionName() != null) {
      return context.getString(
          R.string.out_of_role_description_with_name,
          collectionState.getCollectionRoleDescription(),
          collectionState.getCollectionName());
    } else {
      return context.getString(
          R.string.out_of_role_description, collectionState.getCollectionRoleDescription());
    }
  }

  private static CharSequence getCollectionGridPagerEnter(
      CollectionState collectionState, Context context) {
    return CompositorUtils.joinCharSequences(
        getCollectionName(
            R.string.in_grid_pager,
            R.string.in_grid_pager_with_name,
            collectionState.getCollectionName(),
            context),
        getCollectionLevel(collectionState, context),
        hasBothCount(collectionState)
            ? CompositorUtils.joinCharSequences(
                context.getString(
                    R.string.row_index_template,
                    getCollectionTableItemRowIndex(collectionState) + 1),
                context.getString(
                        R.string.column_index_template,
                        getCollectionTableItemColumnIndex(collectionState))
                    + 1)
            : null,
        quantityCharSequence(
            R.plurals.template_list_row_count,
            collectionState.getCollectionRowCount(),
            collectionState.getCollectionRowCount(),
            context),
        quantityCharSequence(
            R.plurals.template_list_column_count,
            collectionState.getCollectionColumnCount(),
            collectionState.getCollectionColumnCount(),
            context));
  }

  private static CharSequence getCollectionVerticalPagerEnter(
      CollectionState collectionState, Context context) {
    int tableItemRowIndex = getCollectionTableItemRowIndex(collectionState);
    return CompositorUtils.joinCharSequences(
        getCollectionName(
            R.string.in_vertical_pager,
            R.string.in_vertical_pager_with_name,
            collectionState.getCollectionName(),
            context),
        getCollectionLevel(collectionState, context),
        tableItemRowIndex >= 0
            ? context.getString(
                R.string.template_viewpager_index_count,
                tableItemRowIndex + 1,
                collectionState.getCollectionRowCount())
            : null);
  }

  private static CharSequence getCollectionHorizontalPagerEnter(
      CollectionState collectionState, Context context) {
    int tableItemColumnIndex = getCollectionTableItemColumnIndex(collectionState);
    return CompositorUtils.joinCharSequences(
        getCollectionName(
            R.string.in_horizontal_pager,
            R.string.in_horizontal_pager_with_name,
            collectionState.getCollectionName(),
            context),
        getCollectionLevel(collectionState, context),
        tableItemColumnIndex >= 0
            ? context.getString(
                R.string.template_viewpager_index_count,
                tableItemColumnIndex + 1,
                collectionState.getCollectionColumnCount())
            : null);
  }

  private static boolean hasAnyCount(CollectionState collectionState) {
    return hasRowCount(collectionState) || hasColumnCount(collectionState);
  }
}
