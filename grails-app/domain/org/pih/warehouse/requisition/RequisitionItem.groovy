/**
* Copyright (c) 2012 Partners In Health.  All rights reserved.
* The use and distribution terms for this software are covered by the
* Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
* which can be found in the file epl-v10.html at the root of this distribution.
* By using this software in any fashion, you are agreeing to be bound by
* the terms of this license.
* You must not remove this notice, or any other, from this software.
**/ 
package org.pih.warehouse.requisition

import grails.validation.ValidationException
import org.pih.warehouse.auth.AuthService
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.Person
import org.pih.warehouse.core.User
import org.pih.warehouse.inventory.Inventory;
import org.pih.warehouse.inventory.InventoryItem;
import org.pih.warehouse.picklist.PicklistItem;
import org.pih.warehouse.product.Category;
import org.pih.warehouse.product.Product;
import org.pih.warehouse.product.ProductGroup
import org.pih.warehouse.product.ProductPackage

class RequisitionItem implements Comparable<RequisitionItem>, Serializable {

    def beforeInsert = {
        def currentUser = AuthService.currentUser.get()
        if (currentUser) {
            createdBy = currentUser
            updatedBy = currentUser
        }
    }
    def beforeUpdate = {
        def currentUser = AuthService.currentUser.get()
        if (currentUser) {
            updatedBy = currentUser
        }
    }

    String id
	String description	
	
	// Requested item or product
    Product product
    Category category
	InventoryItem inventoryItem
    RequisitionItemType requisitionItemType = RequisitionItemType.ORIGINAL
    ProductGroup productGroup
	ProductPackage productPackage
    Integer quantity

    // Status is handled dynamically at the moment, but we might want to save it at some point
    //RequisitionItemStatus requisitionItemStatus

    // Cancellation / change
	Integer quantityApproved
    Integer quantityCanceled
	String cancelReasonCode
	String cancelComments

	// Miscellaneous information
	Float unitPrice	
	Person requestedBy	// the person who actually requested the item
	Boolean substitutable = false
    String recipient
    String comment
    Integer orderIndex = 0

	// Parent requisition item
	RequisitionItem parentRequisitionItem
    RequisitionItem substitutionItem
    RequisitionItem modificationItem


	// Audit fields
	Date dateCreated
	Date lastUpdated
    User createdBy
    User updatedBy


    static transients = [ "type" ]
	
	static belongsTo = [ requisition: Requisition ]	
	static hasMany = [ requisitionItems: RequisitionItem, picklistItems: PicklistItem ] // requisitionItems:RequisitionItem,
	
	static mapping = {
		id generator: 'uuid'
        picklistItems cascade: "all-delete-orphan", sort: "id"
		requisitionItems cascade: "all-delete-orphan", sort: "id", batchSize: 100
	}

    static mappedBy = [requisitionItems:'parentRequisitionItem']

    static constraints = {
        requisitionItemType(nullable:true)
    	description(nullable:true)
        category(nullable:true)
        product(nullable:false)
        productGroup(nullable:true)
        productPackage(nullable:true)
        inventoryItem(nullable:true)
        requestedBy(nullable:true)
        quantity(nullable:false, min:1)
        quantityApproved(nullable: true)
        quantityCanceled(nullable:true,
            validator: { value, obj->
                // Must have a cancel reason code
                if (value > 0 && !obj.cancelReasonCode) {
                    return false
                }
                else {
                    return true
                }
            })
		cancelReasonCode(nullable:true)
		cancelComments(nullable:true)
        unitPrice(nullable:true)
        substitutable(nullable:false)
        comment(nullable:true)
        recipient(nullable:true)
        orderIndex(nullable: true)
		parentRequisitionItem(nullable:true)
        createdBy(nullable: true)
        updatedBy(nullable: true)
	}

    /**
     * @return
     */
    def getStatus() {
        if (isApproved() || parentRequisitionItem) { return RequisitionItemStatus.APPROVED }
        else if (isSubstituted()) { return RequisitionItemStatus.SUBSTITUTED }
        else if (isChanged()) { return RequisitionItemStatus.CHANGED }
        else if (isCanceled()) { return RequisitionItemStatus.CANCELED }
        else if (isCompleted()) { return RequisitionItemStatus.COMPLETED }
        else { return RequisitionItemStatus.PENDING }
    }


    /**
     * We currently only support quantity change and substitution so there will be,
     * at most, one child requisition item.
     *
     * @return the child requisition item that represents the quantity change
     */
    def getChange() {
        //return (requisitionItems?.size() > 0) ? requisitionItems?.asList()?.first() : null
        return modificationItem?:substitutionItem
    }

    /**
     * We currently only support quantity change and substitution so there will be,
     * at most, one child requisition item.
     *
     * @return the child requisition item that represents the substitution
     */
    def getSubstitution() {
        //return (requisitionItems?.size() > 0) ? requisitionItems?.asList()?.first() : null
        return substitutionItem?:modificationItem
    }
    /**
     * Undo any changes made to this requisition item.
     */
    def undoChanges() {
        quantityApproved = 0
        quantityCanceled = 0
        cancelComments = null
        cancelReasonCode = null

        if (substitutionItem) {
            requisition.removeFromRequisitionItems(substitutionItem)
            substitutionItem.delete()
        }

        if (modificationItem) {
            requisition.removeFromRequisitionItems(modificationItem)
            modificationItem.delete()
        }

        substitutionItem = null
        modificationItem = null
        // Need to remove from both associations
        if (requisitionItems) {
            requisitionItems.each {
                requisition.removeFromRequisitionItems(it)
                removeFromRequisitionItems(it)
            }
        }
    }

    /**
     * Allow user to change the quantity of a requisition item.
     *
     * @param newQuantity
     * @param reasonCode
     * @param comments
     * @return
     */
    def changeQuantity(Integer newQuantity, String reasonCode, String comments) {
        changeQuantity(newQuantity, null, reasonCode, comments)
    }

    /**
     * Allow user to change the quantity of a requisition item.
     *
     * @param newQuantity
     * @param reasonCode
     * @param comments
     * @return
     */
    def changeQuantity(Integer newQuantity, ProductPackage newProductPackage, String reasonCode, String comments) {

        println "Change quantity: " + newQuantity + " " + reasonCode + " " + comments
        // And then create a new requisition item for the remaining quantity (if not 0)
        if (newQuantity == 0) {
            cancelQuantity(reasonCode, comments)
        }
        else {

            if (newProductPackage == productPackage && newQuantity == quantity) {
                errors.rejectValue("quantity","requisitionItem.mustChangeQuantityOrPackage.message")
            }
            if (newQuantity < 0) {
                errors.rejectValue("quantity","requisitionItem.quantityMustBeGreaterThanZero.message")
            }
            if (!reasonCode) {
                errors.rejectValue("cancelReasonCode","requisitionItem.invalidReasonCode.message")
            }
            if (hasErrors() || !validate()) {
                throw new ValidationException("Validation errors on requisition item", errors)
            }

            // First we want to cancel the current requisition item
            cancelQuantity(reasonCode, comments)

            // TODO Refactor the following logic into a business method

            // And then create a new requisition item to represent the new quantity
            modificationItem = new RequisitionItem()
            modificationItem.requisitionItemType =
                newProductPackage?RequisitionItemType.PACKAGE_CHANGE:RequisitionItemType.QUANTITY_CHANGE

            modificationItem.requisition = requisition
            modificationItem.product = product
            modificationItem.productPackage = newProductPackage?:productPackage
            modificationItem.parentRequisitionItem = this
            modificationItem.orderIndex = orderIndex
            modificationItem.quantity = newQuantity
            modificationItem.quantityApproved = newQuantity
            modificationItem.save(flush: true, failOnError: true)
        }
    }

    /**
     *
     * @param product
     * @param newProductPackage
     * @param newQuantity
     * @param reasonCode
     * @param comments
     * @return
     */
    def chooseSubstitute(Product newProduct, ProductPackage newProductPackage, Integer newQuantity, String reasonCode, String comments) {

        if (!newProduct || newProduct == product) {
            errors.rejectValue("product", "requisitionItem.product.invalid")
        }
        if (newQuantity <= 0) {
            errors.rejectValue("quantity","requisitionItem.quantity.invalid")
        }
        if (!reasonCode) {
            errors.rejectValue("cancelReasonCode","requisitionItem.reasonCode.invalid")
        }
        if (hasErrors() || !validate()) {
            throw new ValidationException("Validation errors on requisition item", errors)
        }
        // First we want to cancel the current requisition item
        cancelQuantity(reasonCode, comments)

        // And then create a new requisition item to represent the new quantity
        substitutionItem = new RequisitionItem()
        substitutionItem.requisition = requisition
        substitutionItem.requisitionItemType = RequisitionItemType.SUBSTITUTION
        substitutionItem.product = newProduct
        substitutionItem.productPackage = newProductPackage?:productPackage
        substitutionItem.quantity = newQuantity
        substitutionItem.quantityApproved = newQuantity
        substitutionItem.parentRequisitionItem = this
        substitutionItem.orderIndex = orderIndex
        substitutionItem.save(flush: true, failOnError: true)
    }

    /**
     *
     * @param reasonCode
     * @param comments
     * @return
     */
    def cancelQuantity(reasonCode, comments) {

        if (isCanceled()) {
            errors.reject("requisitionItem.alreadyCancelled.message")
        }
        if (!reasonCode) {
            errors.rejectValue("cancelReasonCode","requisitionItem.reasonCode.invalid")
        }
        if (hasErrors() || !validate()) {
            throw new ValidationException("Validation errors on requisition item", errors)
        }

        // Remove all picklist items
        def picklistItems = getPicklistItems()
        picklistItems.each {
            removeFromPicklistItems(it)
            it.picklist.removeFromPicklistItems(it)
            it.delete()
        }

        quantityCanceled = quantity
        cancelReasonCode = reasonCode
        cancelComments = comments
    }


    def approveQuantity() {
        if (quantityCanceled >= quantity) {
            errors.rejectValue("quantityApproved","requisitionItem.quantityApproved.invalid")
            throw new ValidationException("Quantity cancelled already exceeds or equals quantity requested. Undo previous changes and try again.", errors)
        }
        else {
            quantityApproved = (quantity?:0) - (quantityCanceled?:0)
        }
    }

    /**
     * @return  the quantity (in uom) that has not been canceled
     */
    def quantityNotCanceled() {
        return quantity ? (quantity?:0) - (quantityCanceled?:0) : 0
    }

    /**
     * Return the package quantity multiplied by the quantity requested.
     *
     * @return
     */
    def totalQuantity() {
        return (productPackage?.quantity?:1) * (quantity?:0)
    }

    def totalQuantityCanceled() {
        return (productPackage?.quantity?:1) * (quantityCanceled?:0)
    }

    def totalQuantityApproved() {
        return (productPackage?.quantity?:1) * (quantityApproved?:0)
    }


    def totalQuantityNotCanceled() {
        return (productPackage?.quantity?:1) * (quantityNotCanceled()?:0)
    }

    def totalQuantityPicked() {
        return calculateQuantityPicked()
    }

    def totalQuantityRemaining() {
        return calculateQuantityRemaining()
    }

    /**
     * @return true if the requisition item has been completely canceled
     */
    def isCanceled() {
        return totalQuantityCanceled() == totalQuantity() && !modificationItem && !substitutionItem && !requisitionItems
    }

    /**
     * @return true if the requisition item has any child requisition items or has any quantity canceled
     */
    def isChanged() {
        def startTime = System.currentTimeMillis()
        def isChanged = quantityCanceled > 0 && (modificationItem || substitutionItem || requisitionItems)

    }

    /**
     * @return  true if this child requisition item's parent is canceled and the child product and product package is different from its parent
     */
    def isSubstituted() {
        def isSubstituted = requisitionItems.any { it.requisitionItemType == RequisitionItemType.SUBSTITUTION }
        return isSubstituted || (quantityCanceled > 0 && substitutionItem)
    }

    def isCanceledOrSubstituted() {
        return isCanceled() || isSubstituted()
    }


    /**
     * @return true if the requisition is no longer in the reviewing stage and there are no changes
     */
    def isApproved() {
        //return requisition?.status > RequisitionStatus.REVIEWING && !isChanged()
        return quantityApproved > 0
    }

    def isPending() {
        return !(isApproved() || isSubstituted() || isChanged() || isCanceled())
    }


    /**
     * @return  true if this child requisition item's parent is canceled and the child product and product package is different from its parent
     */
    def isSubstitution() {
        return parentRequisitionItem?.isChanged() && (parentRequisitionItem?.product != product) && (parentRequisitionItem?.productPackage != productPackage)
    }

    def isPartiallyFulfilled() {
        return totalQuantityPicked() > 0 && totalQuantityRemaining() > 0
    }

    def isFulfilled() {
        return totalQuantityPicked() >= totalQuantity()
    }

    def isCompleted() {
        return calculatePercentageCompleted() >= 100
    }

    /**
     * @return  true if the item has been completed cancelled and has some child items that are substitutes
     */
    def hasSubstitution() {
        def hasSubstitution = requisitionItems?.any { it.requisitionItemType == RequisitionItemType.SUBSTITUTION }
        return hasSubstitution || substitutionItem!=null
    }

    def canUndoChanges() {
        def startTime = System.currentTimeMillis()
        def canUndoChanges = isChanged() || isApproved() || isCanceled()

        //println "canUndoChanges: " + (System.currentTimeMillis() - startTime) + " ms"
        return canUndoChanges
    }

    def canApproveQuantity() {
        def startTime = System.currentTimeMillis()
        def canChangeQuantity = !isChanged() && !isApproved() && !isCanceled()

        //println "canChangeQuantity: " + (System.currentTimeMillis() - startTime) + " ms"
        return canChangeQuantity
    }

    def canChangeQuantity() {
        def startTime = System.currentTimeMillis()
        def canChangeQuantity = !isChanged() && !isApproved() && !isCanceled()

        //println "canChangeQuantity: " + (System.currentTimeMillis() - startTime) + " ms"
        return canChangeQuantity
    }

    def canCancelQuantity() {
        def startTime = System.currentTimeMillis()
        def canCancelQuantity = !isChanged() && !isApproved() && !isCanceled()

        //println "canCancelQuantity: " + (System.currentTimeMillis() - startTime) + " ms"
        return canCancelQuantity
    }

    def canChooseSubstitute() {
        def startTime = System.currentTimeMillis()
        def canChooseSubstitute = !isChanged() && !isApproved() && !isCanceled()

        //println "canChooseSubstitute: " + (System.currentTimeMillis() - startTime) + " ms"
        return canChooseSubstitute
    }

    def calculateQuantityPicked() {
        long startTime = System.currentTimeMillis()
        def quantityPicked = 0
        try {
            quantityPicked = PicklistItem.findAllByRequisitionItem(this).sum { it.quantity }
        } catch (Exception e) {
            println "Error: " + e.message
        }

        println "Calculate quantity picked: " + (System.currentTimeMillis() - startTime) + " ms"

        return quantityPicked?:0
    }

	def calculateQuantityRemaining() {
        long startTime = System.currentTimeMillis()
		def quantityRemaining = totalQuantity() - (totalQuantityPicked() + totalQuantityCanceled())


        //println "calculateQuantityRemaining: " + (System.currentTimeMillis() - startTime) + " ms"
        return quantityRemaining
	}

    def calculateNumInventoryItem(Inventory inventory) {
        long startTime = System.currentTimeMillis()
        def numInventoryItem = InventoryItem.findAllByProduct(product).size()
        //println "calculateNumInventoryItem: " + (System.currentTimeMillis() - startTime) + " ms"
        return numInventoryItem
    }

    def retrievePicklistItems() {
        long startTime = System.currentTimeMillis()
        def picklistItems = PicklistItem.findAllByRequisitionItem(this)
        println "retrievePicklistItems: " + (System.currentTimeMillis() - startTime) + " ms"
        return picklistItems
    }

    def availableInventoryItems() {
        return InventoryItem.findAllByProduct(product)
    }

    def calculatePercentagePicked() {
        return totalQuantity()?(totalQuantityPicked()/totalQuantity())*100:0
    }

    def calculatePercentageCanceled() {
        return totalQuantity()?(totalQuantityCanceled()/totalQuantity())*100:0
    }

    def calculatePercentageCompleted() {
        return totalQuantity()?((totalQuantityPicked()+totalQuantityCanceled()+totalQuantityApproved())/totalQuantity())*100:0
    }

    def calculatePercentageRemaining() {
        return totalQuantity()?(totalQuantityRemaining()/totalQuantity())*100:0
    }

    def next() {
        def requisitionItems = requisition.requisitionItems as List
        def currentIndex = requisitionItems.findIndexOf { it == this }
        def nextItem = requisitionItems[currentIndex+1]?:requisitionItems[0]
        return nextItem
    }

    def previous() {
        def requisitionItems = requisition.requisitionItems as List
        def lastIndex = requisitionItems?.size()-1
        def currentIndex = requisitionItems.findIndexOf { it == this }
        def previousItem = requisitionItems[currentIndex-1]?:requisitionItems[lastIndex]
        return previousItem
    }

    RequisitionItem newInstance() {
        return new RequisitionItem()
    }

    /**
     * Sort by sort order, name
     *
     * Sort requisitions by receiving location (alphabetical), requisition type, commodity class (consumables or medications), then date requested, then date created,
     */
    int compareTo(RequisitionItem requisitionItem) {
        return orderIndex <=> requisitionItem.orderIndex ?:
            requisitionItemType <=> requisitionItem?.requisitionItemType ?:
                id <=> requisitionItem?.id
    }


    Map toJson(){
        [
            id: id,
            version: version,
            productId: product?.id,
            productName: product?.productCode + " " + product?.name + ((productPackage) ? " ("+productPackage?.uom?.code + "/" + productPackage?.quantity + ")" : " (EA/1)"),
            productPackageId: productPackage?.id,
            productPackageName: productPackage ? (productPackage?.uom?.code + "/" + productPackage?.quantity) : null,
            productPackageQuantity: productPackage?.quantity?:1,
            unitOfMeasure: product?.unitOfMeasure?:"EA",
            quantity:quantity,
            requisitionItemType: requisitionItemType,
            status: getStatus(),
            totalQuantity:totalQuantity(),
            quantityCanceled:quantityCanceled,
            totalQuantityCanceled:totalQuantityCanceled(),
            comment: comment,
            recipient: recipient,
            substitutable: substitutable,
            isChanged: isChanged(),
            isSubstituted: isSubstituted(),
            isPending: isPending(),
            isSubstitution: isSubstitution(),
            isCompleted: isCompleted(),
            isApproved: isApproved(),
            isCanceled: isCanceled(),
            orderIndex: orderIndex
        ]
    }


}
