/**
 * Copyright (c) 2009 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.controller;

import org.candlepin.audit.Event;
import org.candlepin.audit.EventFactory;
import org.candlepin.audit.EventSink;
import org.candlepin.config.Config;
import org.candlepin.config.ConfigProperties;
import org.candlepin.model.Consumer;
import org.candlepin.model.ConsumerCurator;
import org.candlepin.model.Entitlement;
import org.candlepin.model.EntitlementCertificate;
import org.candlepin.model.EntitlementCertificateCurator;
import org.candlepin.model.EntitlementCurator;
import org.candlepin.model.Owner;
import org.candlepin.model.Pool;
import org.candlepin.model.PoolCurator;
import org.candlepin.model.Product;
import org.candlepin.model.Subscription;
import org.candlepin.policy.Enforcer;
import org.candlepin.policy.EntitlementRefusedException;
import org.candlepin.policy.PoolRules;
import org.candlepin.policy.ValidationResult;
import org.candlepin.policy.js.compliance.ComplianceRules;
import org.candlepin.policy.js.compliance.ComplianceStatus;
import org.candlepin.policy.js.entitlement.PreEntHelper;
import org.candlepin.policy.js.entitlement.PreUnbindHelper;
import org.candlepin.policy.js.pool.PoolHelper;
import org.candlepin.policy.js.pool.PoolUpdate;
import org.candlepin.service.EntitlementCertServiceAdapter;
import org.candlepin.service.ProductServiceAdapter;
import org.candlepin.service.SubscriptionServiceAdapter;

import com.google.inject.Inject;
import com.wideplay.warp.persist.Transactional;

import edu.emory.mathcs.backport.java.util.Arrays;

import org.apache.log4j.Logger;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * PoolManager
 */
public class CandlepinPoolManager implements PoolManager {

    private PoolCurator poolCurator;
    private static Logger log = Logger.getLogger(CandlepinPoolManager.class);

    private SubscriptionServiceAdapter subAdapter;
    private ProductServiceAdapter productAdapter;
    private EventSink sink;
    private EventFactory eventFactory;
    private Config config;
    private Enforcer enforcer;
    private PoolRules poolRules;
    private EntitlementCurator entitlementCurator;
    private ConsumerCurator consumerCurator;
    private EntitlementCertServiceAdapter entCertAdapter;
    private EntitlementCertificateCurator entitlementCertificateCurator;
    private ComplianceRules complianceRules;

    /**
     * @param poolCurator
     * @param subAdapter
     * @param sink
     * @param eventFactory
     * @param config
     */
    @Inject
    public CandlepinPoolManager(PoolCurator poolCurator,
        SubscriptionServiceAdapter subAdapter,
        ProductServiceAdapter productAdapter,
        EntitlementCertServiceAdapter entCertAdapter, EventSink sink,
        EventFactory eventFactory, Config config, Enforcer enforcer,
        PoolRules poolRules, EntitlementCurator curator1,
        ConsumerCurator consumerCurator, EntitlementCertificateCurator ecC,
        ComplianceRules complianceRules) {

        this.poolCurator = poolCurator;
        this.subAdapter = subAdapter;
        this.productAdapter = productAdapter;
        this.sink = sink;
        this.eventFactory = eventFactory;
        this.config = config;
        this.entitlementCurator = curator1;
        this.consumerCurator = consumerCurator;
        this.enforcer = enforcer;
        this.poolRules = poolRules;
        this.entCertAdapter = entCertAdapter;
        this.entitlementCertificateCurator = ecC;
        this.complianceRules = complianceRules;
    }

    /**
     * Check our underlying subscription service and update the pool data. Note
     * that refreshing the pools doesn't actually take any action, should a
     * subscription be reduced, expired, or revoked. Pre-existing entitlements
     * will need to be dealt with separately from this event.
     *
     * @param owner Owner to be refreshed.
     */
    public void refreshPools(Owner owner) {
        if (log.isDebugEnabled()) {
            log.debug("Refreshing pools");
        }

        List<Subscription> subs = subAdapter.getSubscriptions(owner);

        if (log.isDebugEnabled()) {
            log.debug("Found subscriptions: ");
            for (Subscription sub : subs) {
                log.debug("   " + sub);
            }
        }

        List<Pool> pools = this.poolCurator.listAvailableEntitlementPools(null,
            owner, (String) null, null, false, false);

        if (log.isDebugEnabled()) {
            log.debug("Found pools: ");
            for (Pool p : pools) {
                log.debug("   " + p);
            }
        }

        // Map all pools for this owner/product that have a
        // subscription ID associated with them.
        Map<String, List<Pool>> subToPoolMap = new HashMap<String, List<Pool>>();
        for (Pool p : pools) {
            if (p.getSubscriptionId() != null) {
                if (!subToPoolMap.containsKey(p.getSubscriptionId())) {
                    subToPoolMap.put(p.getSubscriptionId(),
                        new LinkedList<Pool>());
                }
                subToPoolMap.get(p.getSubscriptionId()).add(p);
            }
        }

        for (Subscription sub : subs) {
            if (!poolExistsForSubscription(subToPoolMap, sub.getId())) {
                this.createPoolsForSubscription(sub);
                subToPoolMap.remove(sub.getId());
            }
            else {
                updatePoolsForSubscription(subToPoolMap.get(sub.getId()), sub);
                subToPoolMap.remove(sub.getId());
            }
        }

        // delete pools whose subscription disappeared:
        for (Entry<String, List<Pool>> entry : subToPoolMap.entrySet()) {
            for (Pool p : entry.getValue()) {
                deletePool(p);
            }
        }
    }

    private void deleteExcessEntitlements(Pool existingPool) {
        boolean lifo = !config
            .getBoolean(ConfigProperties.REVOKE_ENTITLEMENT_IN_FIFO_ORDER);

        if (existingPool.isOverflowing()) {
            // if pool quantity has reduced, then start with revocation.
            Iterator<Entitlement> iter = this.poolCurator
                .retrieveFreeEntitlementsOfPool(existingPool, lifo).iterator();

            long consumed = existingPool.getConsumed();
            while ((consumed > existingPool.getQuantity()) && iter.hasNext()) {
                Entitlement e = iter.next();
                revokeEntitlement(e);
                consumed -= e.getQuantity();
            }
        }
    }

    /**
     * Update pool for subscription. - This method only checks for change in
     * quantity and dates of a subscription. Currently any quantity changes in
     * pool are not handled.
     *
     * @param existingPools the existing pools
     * @param sub the sub
     */
    public void updatePoolsForSubscription(List<Pool> existingPools,
        Subscription sub) {

        /*
         * Rules need to determine which pools have changed, but the Java must
         * send out the events. Create an event for each pool that could change,
         * even if we won't use them all.
         */
        Map<String, Event> poolEvents = new HashMap<String, Event>();
        for (Pool existing : existingPools) {
            Event e = eventFactory.poolChangedFrom(existing);
            poolEvents.put(existing.getId(), e);
        }

        // Hand off to Javascript to determine which pools need updating:
        List<PoolUpdate> updatedPools = poolRules.updatePools(sub,
            existingPools);

        for (PoolUpdate updatedPool : updatedPools) {

            Pool existingPool = updatedPool.getPool();

            // quantity has changed. delete any excess entitlements from pool
            if (updatedPool.getQuantityChanged()) {
                this.deleteExcessEntitlements(existingPool);
            }

            // dates changed. regenerate all entitlement certificates
            if (updatedPool.getDatesChanged() ||
                updatedPool.getProductsChanged()) {
                List<Entitlement> entitlements = poolCurator
                    .retrieveFreeEntitlementsOfPool(existingPool, true);

                // when subscription dates change, entitlement dates should
                // change as well
                for (Entitlement entitlement : entitlements) {
                    entitlement.setStartDate(sub.getStartDate());
                    entitlement.setEndDate(sub.getEndDate());
                    // TODO: perhaps optimize it to use hibernate query?
                    this.entitlementCurator.merge(entitlement);
                }
                regenerateCertificatesOf(entitlements);
            }
            // save changes for the pool
            this.poolCurator.merge(existingPool);

            eventFactory.poolChangedTo(poolEvents.get(existingPool.getId()),
                existingPool);
            sink.sendEvent(poolEvents.get(existingPool.getId()));
        }
    }

    public void updatePoolForSubscription(Pool existingPool, Subscription sub) {
        List<Pool> tempList = new LinkedList<Pool>();
        tempList.add(existingPool);
        updatePoolsForSubscription(tempList, sub);
    }

    private boolean poolExistsForSubscription(
        Map<String, List<Pool>> subToPoolMap, String id) {
        return subToPoolMap.containsKey(id);
    }

    /**
     * @param sub
     * @return the newly created Pool
     */
    public List<Pool> createPoolsForSubscription(Subscription sub) {
        if (log.isDebugEnabled()) {
            log.debug("Creating new pool for new sub: " + sub.getId());
        }

        List<Pool> pools = poolRules.createPools(sub);
        for (Pool pool : pools) {
            createPool(pool);
        }

        return pools;
    }

    public Pool createPool(Pool p) {
        Pool created = poolCurator.create(p);
        if (log.isDebugEnabled()) {
            log.debug("   new pool: " + p);
        }
        if (created != null) {
            sink.emitPoolCreated(created);
        }

        return created;
    }

    public Pool find(String poolId) {
        return this.poolCurator.find(poolId);
    }

    public List<Pool> lookupBySubscriptionId(String id) {
        return this.poolCurator.lookupBySubscriptionId(id);
    }

    public PoolCurator getPoolCurator() {
        return this.poolCurator;
    }

    /**
     * Request an entitlement by product. If the entitlement cannot be granted,
     * null will be returned. TODO: Throw exception if entitlement not granted.
     * Report why.
     *
     * @param consumer consumer requesting to be entitled
     * @param productIds products to be entitled.
     * @param entitleDate specific date to entitle by.
     * @return Entitlement
     * @throws EntitlementRefusedException if entitlement is refused
     */
    //
    // NOTE: after calling this method both entitlement pool and consumer
    // parameters
    // will most certainly be stale. beware!
    //
    @Transactional
    public List<Entitlement> entitleByProducts(Consumer consumer,
        String[] productIds, Date entitleDate)
        throws EntitlementRefusedException {
        Owner owner = consumer.getOwner();
        List<Entitlement> entitlements = new LinkedList<Entitlement>();

        // Use the current date if one wasn't provided:
        if (entitleDate == null) {
            entitleDate = new Date();
        }

        ValidationResult failedResult = null;
        List<Pool> allOwnerPools = poolCurator.listByOwner(owner, entitleDate);
        List<Pool> filteredPools = new LinkedList<Pool>();

        // We have to check compliance status here so we can replace an empty
        // array of product IDs with the array the consumer actually needs. (i.e. during
        // a healing request)
        ComplianceStatus compliance = complianceRules.getStatus(consumer, entitleDate);
        if (productIds == null || productIds.length == 0) {
            log.debug("No products specified for bind, checking compliance to see what " +
                "is needed.");
            Set<String> tmpSet = new HashSet<String>();
            tmpSet.addAll(compliance.getNonCompliantProducts());
            tmpSet.addAll(compliance.getPartiallyCompliantProducts().keySet());
            productIds = tmpSet.toArray(new String [] {});
        }

        log.info("Attempting auto-bind for products on date: " + entitleDate);
        for (String productId : productIds) {
            log.info("  " + productId);
        }

        for (Pool pool : allOwnerPools) {
            boolean providesProduct = false;
            for (String productId : productIds) {
                if (pool.provides(productId)) {
                    providesProduct = true;
                    break;
                }
            }
            if (providesProduct) {
                PreEntHelper preHelper = enforcer.preEntitlement(consumer,
                    pool, 1);
                ValidationResult result = preHelper.getResult();

                if (result.hasErrors() || result.hasWarnings()) {
                    // Just keep the last one around, if we need it
                    failedResult = result;
                    if (log.isDebugEnabled()) {
                        log.debug("Pool filtered from candidates due to rules " +
                            "failure: " +
                            pool.getId());
                    }
                }
                else {
                    filteredPools.add(pool);
                }
            }
        }

        if (filteredPools.size() == 0) {
            // Only throw refused exception if we actually hit the rules:
            if (failedResult != null) {
                throw new EntitlementRefusedException(failedResult);
            }
            throw new RuntimeException("No entitlements for products: " +
                Arrays.toString(productIds));
        }

        Map<Pool, Integer> bestPools = enforcer.selectBestPools(consumer,
            productIds, filteredPools, compliance);
        if (bestPools == null) {
            throw new RuntimeException("No entitlements for products: " +
                Arrays.toString(productIds));
        }

        // now make the entitlements
        for (Entry<Pool, Integer> entry : bestPools.entrySet()) {
            entitlements.add(addEntitlement(consumer, entry.getKey(),
                entry.getValue(), false));
        }

        return entitlements;
    }

    public Entitlement entitleByProduct(Consumer consumer, String productId)
        throws EntitlementRefusedException {
        // There will only be one returned entitlement, anyways
        return entitleByProducts(consumer, new String[]{ productId }, null).get(0);
    }

    /**
     * Request an entitlement by pool.. If the entitlement cannot be granted,
     * null will be returned. TODO: Throw exception if entitlement not granted.
     * Report why.
     *
     * @param consumer consumer requesting to be entitled
     * @param pool entitlement pool to consume from
     * @return Entitlement
     * @throws EntitlementRefusedException if entitlement is refused
     */
    @Transactional
    public Entitlement entitleByPool(Consumer consumer, Pool pool,
        Integer quantity) throws EntitlementRefusedException {
        return addEntitlement(consumer, pool, quantity, false);
    }

    @Transactional
    public Entitlement ueberCertEntitlement(Consumer consumer, Pool pool,
        Integer quantity) throws EntitlementRefusedException {
        return addEntitlement(consumer, pool, 1, true);
    }

    private Entitlement addEntitlement(Consumer consumer, Pool pool,
        Integer quantity, boolean generateUeberCert) throws EntitlementRefusedException {

        // Because there are several paths to this one place where entitlements
        // are granted, we cannot be positive the caller obtained a lock on the
        // pool
        // when it was read. As such we're going to reload it with a lock
        // before starting this process.
        pool = poolCurator.lockAndLoad(pool);

        /* XXX: running pre rules twice on the entitle by product case */
        PreEntHelper preHelper = enforcer.preEntitlement(consumer, pool,
            quantity);
        ValidationResult result = preHelper.getResult();

        if (!result.isSuccessful()) {
            log.warn("Entitlement not granted: " +
                result.getErrors().toString());
            throw new EntitlementRefusedException(result);
        }

        Entitlement e = new Entitlement(pool, consumer, pool.getStartDate(),
            pool.getEndDate(), quantity);
        consumer.addEntitlement(e);
        pool.getEntitlements().add(e);

        PoolHelper poolHelper = new PoolHelper(this, productAdapter, e);
        enforcer.postEntitlement(consumer, poolHelper, e);

        entitlementCurator.create(e);
        consumerCurator.update(consumer);

        generateEntitlementCertificate(consumer, pool, e, generateUeberCert);
        for (Entitlement regenEnt : entitlementCurator.listModifying(e)) {
            this.regenerateCertificatesOf(regenEnt, generateUeberCert);
        }

        // The quantity is calculated at fetch time. We update it here
        // To reflect what we just added to the db.
        pool.setConsumed(pool.getConsumed() + quantity);
        return e;
    }

    /**
     * @param consumer
     * @param pool
     * @param e
     * @param mergedPool
     * @return
     */
    private EntitlementCertificate generateEntitlementCertificate(
        Consumer consumer, Pool pool, Entitlement e, boolean generateUeberCert) {
        Subscription sub = null;
        if (pool.getSubscriptionId() != null) {
            sub = subAdapter.getSubscription(pool.getSubscriptionId());
        }

        Product product = null;
        if (sub != null) {
            // Just look this up off of the subscription if one exists
            product = sub.getProduct();
        }
        else {
            // This is possible in a sub-pool, for example - the pool was not
            // created directly from a subscription
            product = productAdapter.getProductById(e.getProductId());

            // in the case of a sub-pool, we want to find the originating
            // subscription for cert generation
            sub = findSubscription(e);
        }

        // TODO: Assuming every entitlement = generate a cert, most likely we'll
        // want
        // to know if this product entails granting a cert someday.
        try {
            return generateUeberCert ?
                entCertAdapter.generateUeberCert(e, sub, product) :
                entCertAdapter.generateEntitlementCert(e, sub, product);
        }
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private Subscription findSubscription(Entitlement entitlement) {
        Pool pool = entitlement.getPool();

        if (pool.getSubscriptionId() != null) {
            return subAdapter.getSubscription(pool.getSubscriptionId());
        }

        Entitlement source = pool.getSourceEntitlement();

        if (source != null) {
            return findSubscription(source);
        }

        // Cannot traverse any further - just give up
        return null;
    }

    public void regenerateEntitlementCertificates(Consumer consumer) {
        log.info("Regenerating #" + consumer.getEntitlements().size() +
            " entitlement's certificates for consumer :" + consumer);
        // TODO - Assumes only 1 entitlement certificate exists per entitlement
        this.regenerateCertificatesOf(consumer.getEntitlements());
        log.info("Completed Regenerating #" +
            consumer.getEntitlements().size() +
            " entitlement's certificates for consumer: " + consumer);
    }

    @Transactional
    public void regenerateCertificatesOf(Iterable<Entitlement> iterable) {
        for (Entitlement e : iterable) {
            regenerateCertificatesOf(e, false);
        }
    }

    /**
     * @param e
     */
    @Transactional
    public void regenerateCertificatesOf(Entitlement e, boolean ueberCertificate) {
        if (log.isDebugEnabled()) {
            log.debug("Revoking entitlementCertificates of : " + e);
        }
        this.entCertAdapter.revokeEntitlementCertificates(e);
        for (EntitlementCertificate ec : e.getCertificates()) {
            if (log.isDebugEnabled()) {
                log.debug("Deleting entitlementCertificate: #" + ec.getId());
            }
            this.entitlementCertificateCurator.delete(ec);
        }
        e.getCertificates().clear();
        // below call creates new certificates and saves it to the backend.
        EntitlementCertificate generated = this.generateEntitlementCertificate(
            e.getConsumer(), e.getPool(), e, ueberCertificate);
        this.entitlementCurator.refresh(e);

        // send entitlement changed event.
        this.sink.sendEvent(this.eventFactory.entitlementChanged(e));
        if (log.isDebugEnabled()) {
            log.debug("Generated entitlementCertificate: #" + generated.getId());
        }
    }

    @Transactional
    public void regenerateCertificatesOf(String productId) {
        List<Pool> poolsForProduct = this.poolCurator
            .listAvailableEntitlementPools(null, null, productId, new Date(),
                false, false);
        for (Pool pool : poolsForProduct) {
            regenerateCertificatesOf(pool.getEntitlements());
        }
    }

    public Iterable<Pool> getListOfEntitlementPoolsForProduct(String productId) {
        return this.poolCurator.listAvailableEntitlementPools(null, null,
            productId, null, false, false);
    }

    @Override
    @Transactional
    public void removeEntitlement(Entitlement entitlement) {
        Consumer consumer = entitlement.getConsumer();
        Pool pool = entitlement.getPool();
        PreUnbindHelper preHelper = enforcer.preUnbind(consumer,
            pool);
        ValidationResult result = preHelper.getResult();

        if (!result.isSuccessful()) {
            if (log.isDebugEnabled()) {
                log.debug("Unbind failure from pool: " +
                    pool.getId() + ", error: " +
                    result.getErrors());
            }
        }

        consumer.removeEntitlement(entitlement);

        // Look for pools referencing this entitlement as their source
        // entitlement and clean them up as well
        for (Pool p : poolCurator.listBySourceEntitlement(entitlement)) {
            for (Entitlement e : p.getEntitlements()) {
                this.revokeEntitlement(e);
            }
            deletePool(p);
        }

        poolCurator.merge(pool);
        entitlementCurator.delete(entitlement);
        Event event = eventFactory.entitlementDeleted(entitlement);

        // The quantity is calculated at fetch time. We update it here
        // To reflect what we just removed from the db.
        pool.setConsumed(pool.getConsumed() - entitlement.getQuantity());
        // post unbind actions
        PoolHelper poolHelper = new PoolHelper(this, productAdapter, entitlement);
        enforcer.postUnbind(consumer, poolHelper, entitlement);

        // Find all of the entitlements that modified the original entitlement,
        // and regenerate those to remove the content sets.
        this.regenerateCertificatesOf(entitlementCurator
            .listModifying(entitlement));

        sink.sendEvent(event);
    }

    @Override
    @Transactional
    public void revokeEntitlement(Entitlement entitlement) {
        entCertAdapter.revokeEntitlementCertificates(entitlement);
        removeEntitlement(entitlement);
    }

    @Override
    @Transactional
    public void revokeAllEntitlements(Consumer consumer) {
        for (Entitlement e : entitlementCurator.listByConsumer(consumer)) {
            revokeEntitlement(e);
        }
    }

    @Override
    @Transactional
    public void removeAllEntitlements(Consumer consumer) {
        for (Entitlement e : entitlementCurator.listByConsumer(consumer)) {
            removeEntitlement(e);
        }
    }

    /**
     * Cleanup entitlements and safely delete the given pool.
     *
     * @param pool
     */
    @Transactional
    public void deletePool(Pool pool) {
        Event event = eventFactory.poolDeleted(pool);

        // Must do a full revoke for all entitlements:
        for (Entitlement e : poolCurator.entitlementsIn(pool)) {
            revokeEntitlement(e);
        }

        poolCurator.delete(pool);
        sink.sendEvent(event);
    }

    /**
     * Adjust the count of a pool.
     *
     * @param pool The pool.
     * @param adjust the long amount to adjust (+/-)
     */
    public void updatePoolQuantity(Pool pool, long adjust) {
        pool = poolCurator.lockAndLoad(pool);
        long newCount = pool.getQuantity() + adjust;
        if (newCount < 0) {
            newCount = 0;
        }
        pool.setQuantity(newCount);
        poolCurator.merge(pool);
    }
}