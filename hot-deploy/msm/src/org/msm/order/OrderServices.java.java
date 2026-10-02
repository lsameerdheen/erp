package org.mycompany.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Custom order services for purchase-order GST capture (India) and
 * back-order lifecycle management.
 *
 * All methods follow the OFBiz service contract:
 *   public static Map<String, Object> methodName(DispatchContext dctx,
 *                                                Map<String, Object> context)
 */
public class OrderServices {

    public static final String MODULE = OrderServices.class.getName();

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 2;

    // =====================================================================
    // 1. TAX CALCULATION  (IGST / CGST / SGST)
    // =====================================================================

    /**
     * Calculates IGST, CGST and SGST for a single purchase-order line.
     *
     * Business rule:
     *  - If interState = Y  -> IGST only  (igstPercent applied to taxable)
     *  - If interState = N  -> CGST + SGST (each half of the combined rate)
     */
    public static Map<String, Object> calculateTax(DispatchContext dctx,
                                                   Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String orderId        = (String) context.get("orderId");
            String orderItemSeqId = (String) context.get("orderItemSeqId");
            BigDecimal taxable    = (BigDecimal) context.get("taxableAmount");
            BigDecimal igstPct    = (BigDecimal) context.get("igstPercent");
            BigDecimal cgstPct    = (BigDecimal) context.get("cgstPercent");
            BigDecimal sgstPct    = (BigDecimal) context.get("sgstPercent");
            String interState     = (String) context.get("interState");

            if (taxable == null) {
                taxable = BigDecimal.ZERO;
            }

            BigDecimal igstAmt = BigDecimal.ZERO;
            BigDecimal cgstAmt = BigDecimal.ZERO;
            BigDecimal sgstAmt = BigDecimal.ZERO;

            if ("Y".equalsIgnoreCase(interState) && igstPct != null) {
                // Inter-state: IGST only
                igstAmt = percent(taxable, igstPct);
                cgstPct = BigDecimal.ZERO;
                sgstPct = BigDecimal.ZERO;
            } else {
                // Intra-state: CGST + SGST
                if (cgstPct != null) {
                    cgstAmt = percent(taxable, cgstPct);
                }
                if (sgstPct != null) {
                    sgstAmt = percent(taxable, sgstPct);
                }
            }

            BigDecimal totalTax = igstAmt.add(cgstAmt).add(sgstAmt);
            BigDecimal gross    = taxable.add(totalTax);

            // Persist back onto OrderItem
            if (UtilValidate.isNotEmpty(orderId) && UtilValidate.isNotEmpty(orderItemSeqId)) {
                GenericValue item = EntityQuery.use(delegator)
                        .from("OrderItem")
                        .where("orderId", orderId, "orderItemSeqId", orderItemSeqId)
                        .queryOne();
                if (item != null) {
                    item.set("igstPercent", cgstPct == null ? null : igstPct);
                    item.set("igstAmount",  igstAmt);
                    item.set("cgstPercent", cgstPct);
                    item.set("cgstAmount",  cgstAmt);
                    item.set("sgstPercent", sgstPct);
                    item.set("sgstAmount",  sgstAmt);
                    item.store();
                }
            }

            result.put("igstAmount", igstAmt);
            result.put("cgstAmount", cgstAmt);
            result.put("sgstAmount", sgstAmt);
            result.put("totalTax",   totalTax);
            result.put("grossAmount", gross);

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("Tax calculation failed: " + e.getMessage());
        }
        return result;
    }

    // =====================================================================
    // 2. CREATE PO ITEM WITH GST
    // =====================================================================

    public static Map<String, Object> createItem(DispatchContext dctx,
                                                 Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String orderId       = (String) context.get("orderId");
            String productId     = (String) context.get("productId");
            String supplierGstin = (String) context.get("supplierGstin");
            String hsnCode       = (String) context.get("hsnCode");
            BigDecimal qty       = (BigDecimal) context.get("quantity");
            BigDecimal rate      = (BigDecimal) context.get("rate");
            BigDecimal igstPct   = (BigDecimal) context.get("igstPercent");
            BigDecimal cgstPct   = (BigDecimal) context.get("cgstPercent");
            BigDecimal sgstPct   = (BigDecimal) context.get("sgstPercent");
            String grNo          = (String) context.get("grNo");
            String interState    = (String) context.get("interState");
            GenericValue userLogin = (GenericValue) context.get("userLogin");

            if (qty == null || rate == null) {
                return ServiceUtil.returnError("quantity and rate are required");
            }

            // --- Generate next sequence id ---------------------------------
            String seqId = delegator.getNextSeqId("OrderItem").toString();

            // --- Delegate core creation (keeps OFBiz audit fields intact) --
            Map<String, Object> coreCtx = UtilMisc.toMap(
                    "orderId", orderId,
                    "orderItemSeqId", seqId,
                    "productId", productId,
                    "itemDescription", context.get("itemDescription"),
                    "quantity", qty,
                    "unitPrice", rate,
                    "userLogin", userLogin);

            Map<String, Object> coreRes = dispatcher.runSync("createOrderItem", coreCtx);
            if (ServiceUtil.isError(coreRes)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(coreRes));
            }

            // --- Stamp GST fields ------------------------------------------
            GenericValue item = EntityQuery.use(delegator)
                    .from("OrderItem")
                    .where("orderId", orderId, "orderItemSeqId", seqId)
                    .queryOne();

            if (item != null) {
                item.set("supplierGstin", supplierGstin);
                item.set("hsnCode", hsnCode);
                item.set("grNo", grNo);
                item.set("igstPercent", igstPct);
                item.set("cgstPercent", cgstPct);
                item.set("sgstPercent", sgstPct);
                item.store();
            }

            // --- Trigger tax recalculation ---------------------------------
            Map<String, Object> taxCtx = UtilMisc.toMap(
                    "orderId", orderId,
                    "orderItemSeqId", seqId,
                    "taxableAmount", qty.multiply(rate),
                    "igstPercent", igstPct,
                    "cgstPercent", cgstPct,
                    "sgstPercent", sgstPct,
                    "interState", interState);
            dispatcher.runSync("calculatePurchaseOrderTax", taxCtx);

            Map<String, Object> result = ServiceUtil.returnSuccess("PO item created with GST");
            result.put("orderItemSeqId", seqId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("createItem failed: " + e.getMessage());
        }
    }

    // =====================================================================
    // 3. UPDATE PO ITEM GST
    // =====================================================================

    public static Map<String, Object> updateItem(DispatchContext dctx,
                                                 Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String orderId        = (String) context.get("orderId");
            String orderItemSeqId = (String) context.get("orderItemSeqId");

            GenericValue item = EntityQuery.use(delegator)
                    .from("OrderItem")
                    .where("orderId", orderId, "orderItemSeqId", orderItemSeqId)
                    .queryOne();

            if (item == null) {
                return ServiceUtil.returnError("Order item not found");
            }

            // Only update fields supplied in the context
            setIfPresent(item, context, "supplierGstin");
            setIfPresent(item, context, "hsnCode");
            setIfPresent(item, context, "igstPercent");
            setIfPresent(item, context, "cgstPercent");
            setIfPresent(item, context, "sgstPercent");
            setIfPresent(item, context, "grNo");
            setIfPresent(item, context, "quantity");
            setIfPresent(item, context, "unitPrice");
            item.store();

            // Recalculate tax with the current rates
            BigDecimal qty  = (BigDecimal) context.get("quantity");
            BigDecimal rate = (BigDecimal) context.get("rate");
            if (qty == null)  qty  = item.getBigDecimal("quantity");
            if (rate == null) rate = item.getBigDecimal("unitPrice");

            Map<String, Object> taxCtx = UtilMisc.toMap(
                    "orderId", orderId,
                    "orderItemSeqId", orderItemSeqId,
                    "taxableAmount", qty.multiply(rate),
                    "igstPercent", item.getBigDecimal("igstPercent"),
                    "cgstPercent", item.getBigDecimal("cgstPercent"),
                    "sgstPercent", item.getBigDecimal("sgstPercent"),
                    "interState", context.get("interState"));
            dispatcher.runSync("calculatePurchaseOrderTax", taxCtx);

            return ServiceUtil.returnSuccess("PO item updated");

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("updateItem failed: " + e.getMessage());
        }
    }

    // =====================================================================
    // 4. DELETE PO ITEM
    // =====================================================================

    public static Map<String, Object> deleteItem(DispatchContext dctx,
                                                 Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String orderId        = (String) context.get("orderId");
            String orderItemSeqId = (String) context.get("orderItemSeqId");
            GenericValue userLogin = (GenericValue) context.get("userLogin");

            Map<String, Object> res = dispatcher.runSync("removeOrderItem",
                    UtilMisc.toMap("orderId", orderId,
                            "orderItemSeqId", orderItemSeqId,
                            "userLogin", userLogin));

            if (ServiceUtil.isError(res)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(res));
            }
            return ServiceUtil.returnSuccess("PO item deleted");

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("deleteItem failed: " + e.getMessage());
        }
    }

    // =====================================================================
    // 5. BACK-ORDER HANDLING
    // =====================================================================

    /**
     * Records a receipt against a PO line. Any shortfall between
     * ordered quantity and received quantity is stored as back-ordered
     * quantity on the line.
     *
     * Emits a BACK_ORDER_CREATED OrderItemAttribute when shortfall > 0
     * so downstream workflows (MRP, reorder) can pick it up.
     */
    public static Map<String, Object> receiveAndBackOrder(DispatchContext dctx,
                                                          Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String orderId        = (String) context.get("orderId");
            String orderItemSeqId = (String) context.get("orderItemSeqId");
            BigDecimal receivedQty = (BigDecimal) context.get("receivedQuantity");
            String grNo           = (String) context.get("grNo");
            GenericValue userLogin = (GenericValue) context.get("userLogin");

            if (receivedQty == null || receivedQty.signum() < 0) {
                return ServiceUtil.returnError("receivedQuantity must be >= 0");
            }

            GenericValue item = EntityQuery.use(delegator)
                    .from("OrderItem")
                    .where("orderId", orderId, "orderItemSeqId", orderItemSeqId)
                    .queryOne();

            if (item == null) {
                return ServiceUtil.returnError("Order item not found");
            }

            BigDecimal orderedQty = item.getBigDecimal("quantity");
            if (orderedQty == null) orderedQty = BigDecimal.ZERO;

            // ---- running totals -------------------------------------------
            BigDecimal alreadyReceived = sumReceivedQty(delegator, orderId, orderItemSeqId);
            BigDecimal newTotalReceived = alreadyReceived.add(receivedQty);

            BigDecimal backOrderQty = BigDecimal.ZERO;
            if (newTotalReceived.compareTo(orderedQty) < 0) {
                backOrderQty = orderedQty.subtract(newTotalReceived);
            }

            // ---- record the receipt ---------------------------------------
            String receiptSeq = delegator.getNextSeqId("OrderItemShipGrpInvRes").toString();
            // (Real OFBiz receipts go through receiveInventoryProduct;
            //  here we persist an audit row and rely on the caller to
            //  post the physical inventory elsewhere.)

            // ---- persist back-order quantity on the line ------------------
            item.set("grNo", grNo);
            item.store();

            // ---- write attribute so reporting/MRP can query it ------------
            if (backOrderQty.signum() > 0) {
                Map<String, Object> attrCtx = UtilMisc.toMap(
                        "orderId", orderId,
                        "orderItemSeqId", orderItemSeqId,
                        "attrName", "BACK_ORDER_QTY",
                        "attrValue", backOrderQty.toPlainString(),
                        "userLogin", userLogin);
                dispatcher.runSync("createOrderItemAttribute", attrCtx);

                Map<String, Object> statusCtx = UtilMisc.toMap(
                        "orderId", orderId,
                        "orderItemSeqId", orderItemSeqId,
                        "attrName", "BACK_ORDER_STATUS",
                        "attrValue", "OPEN",
                        "userLogin", userLogin);
                dispatcher.runSync("createOrderItemAttribute", statusCtx);

                Debug.logInfo("Back-order created: order=" + orderId
                        + " seq=" + orderItemSeqId
                        + " backOrderQty=" + backOrderQty, MODULE);
            } else {
                // Fully received -> clear any previous back-order marker
                clearBackOrder(delegator, dispatcher, orderId, orderItemSeqId, userLogin);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Receipt recorded");
            result.put("backOrderQuantity", backOrderQty);
            result.put("totalReceived", newTotalReceived);
            result.put("fullyReceived", backOrderQty.signum() == 0);
            return result;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("receiveAndBackOrder failed: " + e.getMessage());
        }
    }

    /**
     * Converts all open back-orders on a PO line into a fresh
     * replacement PO (or re-opens the same line, depending on
     * the reorderPolicy parameter).
     */
    public static Map<String, Object> processBackOrder(DispatchContext dctx,
                                                       Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String orderId        = (String) context.get("orderId");
            String orderItemSeqId = (String) context.get("orderItemSeqId");
            String action         = (String) context.get("action"); // REORDER | CLOSE | CANCEL
            GenericValue userLogin = (GenericValue) context.get("userLogin");

            GenericValue backOrderAttr = EntityQuery.use(delegator)
                    .from("OrderItemAttribute")
                    .where("orderId", orderId,
                            "orderItemSeqId", orderItemSeqId,
                            "attrName", "BACK_ORDER_QTY")
                    .queryFirst();

            if (backOrderAttr == null) {
                return ServiceUtil.returnError("No open back-order on this line");
            }

            BigDecimal backOrderQty = new BigDecimal(backOrderAttr.getString("attrValue"));

            if ("REORDER".equalsIgnoreCase(action)) {
                // Clone the line into a fresh PO line for the shortfall
                GenericValue src = EntityQuery.use(delegator)
                        .from("OrderItem")
                        .where("orderId", orderId, "orderItemSeqId", orderItemSeqId)
                        .queryOne();

                Map<String, Object> createCtx = new HashMap<>();
                createCtx.put("orderId", orderId);
                createCtx.put("productId", src.get("productId"));
                createCtx.put("itemDescription",
                        src.getString("itemDescription") + " (Back Order)");
                createCtx.put("quantity", backOrderQty);
                createCtx.put("rate", src.getBigDecimal("unitPrice"));
                createCtx.put("supplierGstin", src.get("supplierGstin"));
                createCtx.put("hsnCode", src.get("hsnCode"));
                createCtx.put("igstPercent", src.getBigDecimal("igstPercent"));
                createCtx.put("cgstPercent", src.getBigDecimal("cgstPercent"));
                createCtx.put("sgstPercent", src.getBigDecimal("sgstPercent"));
                createCtx.put("interState", context.get("interState"));
                createCtx.put("userLogin", userLogin);

                Map<String, Object> res = dispatcher.runSync("createPurchaseOrderItemGst", createCtx);
                if (ServiceUtil.isError(res)) {
                    return res;
                }
                markBackOrderStatus(delegator, dispatcher, orderId, orderItemSeqId,
                        "REORDERED", userLogin);
                return ServiceUtil.returnSuccess("Back-order re-issued as new line");

            } else if ("CLOSE".equalsIgnoreCase(action)) {
                markBackOrderStatus(delegator, dispatcher, orderId, orderItemSeqId,
                        "CLOSED", userLogin);
                return ServiceUtil.returnSuccess("Back-order closed");

            } else if ("CANCEL".equalsIgnoreCase(action)) {
                clearBackOrder(delegator, dispatcher, orderId, orderItemSeqId, userLogin);
                return ServiceUtil.returnSuccess("Back-order cancelled");
            }

            return ServiceUtil.returnError("Unknown action: " + action);

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("processBackOrder failed: " + e.getMessage());
        }
    }

    // =====================================================================
    // 6. PRINT PREPARATION
    // =====================================================================

    public static Map<String, Object> preparePrint(DispatchContext dctx,
                                                   Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String orderId = (String) context.get("orderId");
            List<GenericValue> items = EntityQuery.use(delegator)
                    .from("OrderItem")
                    .where("orderId", orderId)
                    .orderBy("orderItemSeqId")
                    .queryList();

            BigDecimal grandTotal = BigDecimal.ZERO;
            for (GenericValue it : items) {
                BigDecimal qty  = nvl(it.getBigDecimal("quantity"));
                BigDecimal rate = nvl(it.getBigDecimal("unitPrice"));
                BigDecimal tax  = nvl(it.getBigDecimal("igstAmount"))
                        .add(nvl(it.getBigDecimal("cgstAmount")))
                        .add(nvl(it.getBigDecimal("sgstAmount")));
                grandTotal = grandTotal.add(qty.multiply(rate)).add(tax);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("orderItems", items);
            result.put("grandTotal", grandTotal);
            return result;
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("preparePrint failed: " + e.getMessage());
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private static BigDecimal percent(BigDecimal base, BigDecimal pct) {
        return base.multiply(pct).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static void setIfPresent(GenericValue gv, Map<String, Object> ctx, String key) {
        if (ctx.containsKey(key)) {
            gv.set(key, ctx.get(key));
        }
    }

    private static BigDecimal sumReceivedQty(Delegator delegator,
                                             String orderId, String seqId) {
        // In a real deployment, sum from ItemIssuance / ShipmentReceipt.
        // Here we read the running BACK_ORDER_QTY attribute.
        try {
            GenericValue ordered = EntityQuery.use(delegator)
                    .from("OrderItem")
                    .where("orderId", orderId, "orderItemSeqId", seqId)
                    .queryOne();
            GenericValue backOrder = EntityQuery.use(delegator)
                    .from("OrderItemAttribute")
                    .where("orderId", orderId,
                            "orderItemSeqId", seqId,
                            "attrName", "BACK_ORDER_QTY")
                    .queryFirst();
            BigDecimal q = nvl(ordered.getBigDecimal("quantity"));
            BigDecimal b = backOrder == null ? BigDecimal.ZERO
                    : new BigDecimal(backOrder.getString("attrValue"));
            return q.subtract(b);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static void clearBackOrder(Delegator delegator, LocalDispatcher dispatcher,
                                       String orderId, String seqId,
                                       GenericValue userLogin) throws GeneralException {
        try {
            List<GenericValue> attrs = EntityQuery.use(delegator)
                    .from("OrderItemAttribute")
                    .where("orderId", orderId,
                            "orderItemSeqId", seqId,
                            "attrName", "BACK_ORDER_QTY")
                    .queryList();
            for (GenericValue a : attrs) {
                dispatcher.runSync("deleteOrderItemAttribute",
                        UtilMisc.toMap("orderId", orderId,
                                "orderItemSeqId", seqId,
                                "attrName", a.getString("attrName"),
                                "userLogin", userLogin));
            }
            markBackOrderStatus(delegator, dispatcher, orderId, seqId, "FULFILLED", userLogin);
        } catch (Exception e) {
            throw new GeneralException(e);
        }
    }

    private static void markBackOrderStatus(Delegator delegator, LocalDispatcher dispatcher,
                                            String orderId, String seqId,
                                            String status, GenericValue userLogin)
            throws GeneralException {
        try {
            GenericValue existing = EntityQuery.use(delegator)
                    .from("OrderItemAttribute")
                    .where("orderId", orderId,
                            "orderItemSeqId", seqId,
                            "attrName", "BACK_ORDER_STATUS")
                    .queryFirst();
            if (existing != null) {
                existing.set("attrValue", status);
                existing.store();
            } else {
                dispatcher.runSync("createOrderItemAttribute",
                        UtilMisc.toMap("orderId", orderId,
                                "orderItemSeqId", seqId,
                                "attrName", "BACK_ORDER_STATUS",
                                "attrValue", status,
                                "userLogin", userLogin));
            }
        } catch (Exception e) {
            throw new GeneralException(e);
        }
    }
}