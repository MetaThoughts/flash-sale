package com.actionworks.flashsale.app.service.impl;

import com.actionworks.flashsale.app.model.OrderTaskStatus;
import com.actionworks.flashsale.app.model.PlaceOrderTask;
import com.actionworks.flashsale.app.model.builder.PlaceOrderTaskBuilder;
import com.actionworks.flashsale.app.model.command.FlashPlaceOrderCommand;
import com.actionworks.flashsale.app.model.dto.FlashItemDTO;
import com.actionworks.flashsale.app.model.result.AppSingleResult;
import com.actionworks.flashsale.app.model.result.OrderTaskHandleResult;
import com.actionworks.flashsale.app.model.result.OrderTaskSubmitResult;
import com.actionworks.flashsale.app.model.result.PlaceOrderResult;
import com.actionworks.flashsale.app.service.FlashItemAppService;
import com.actionworks.flashsale.app.service.PlaceOrderService;
import com.actionworks.flashsale.app.service.PlaceOrderTaskService;
import com.actionworks.flashsale.app.util.OrderNoGenerateContext;
import com.actionworks.flashsale.app.util.OrderNoGenerateService;
import com.actionworks.flashsale.app.util.OrderTaskIdGenerateService;
import com.actionworks.flashsale.cache.redis.RedisCacheService;
import com.actionworks.flashsale.domain.model.entity.FlashItem;
import com.actionworks.flashsale.domain.model.entity.FlashOrder;
import com.actionworks.flashsale.domain.service.FlashActivityDomainService;
import com.actionworks.flashsale.domain.service.FlashItemDomainService;
import com.actionworks.flashsale.domain.service.FlashOrderDomainService;
import com.alibaba.cola.exception.BizException;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import static com.actionworks.flashsale.app.cache.model.CacheConstatants.HOURS_24;
import static com.actionworks.flashsale.app.exception.AppErrorCode.GET_ITEM_FAILED;
import static com.actionworks.flashsale.app.exception.AppErrorCode.INVALID_PARAMS;
import static com.actionworks.flashsale.app.exception.AppErrorCode.ITEM_NOT_ON_SALE;
import static com.actionworks.flashsale.app.exception.AppErrorCode.PLACE_ORDER_FAILED;
import static com.actionworks.flashsale.app.exception.AppErrorCode.PLACE_ORDER_TASK_ID_INVALID;
import static com.actionworks.flashsale.app.model.builder.FlashOrderAppBuilder.toDomain;

@Service
@ConditionalOnProperty(name = "place_order_type", havingValue = "queued")
public class QueuedPlaceOrderService implements PlaceOrderService {
    public static final String PLACE_ORDER_TASK_ORDER_ID_KEY = "PLACE_ORDER_TASK_ORDER_ID_KEY_";
    private static final Logger logger = LoggerFactory.getLogger(QueuedPlaceOrderService.class);
    @Resource
    private FlashItemAppService flashItemAppService;
    @Resource
    private OrderTaskIdGenerateService orderTaskIdGenerateService;
    @Resource
    private PlaceOrderTaskService placeOrderTaskService;
    @Resource
    private FlashOrderDomainService flashOrderDomainService;
    @Resource
    private FlashItemDomainService flashItemDomainService;
    @Resource
    private FlashActivityDomainService flashActivityDomainService;
    @Resource
    private OrderNoGenerateService orderNoGenerateService;
    @Resource
    private RedisCacheService redisCacheService;

    @PostConstruct
    public void init() {
        logger.info("Queued place order service init.");
    }

    @Override
    public PlaceOrderResult placeOrder(Long userId, FlashPlaceOrderCommand flashPlaceOrderCommand) {
        if (flashPlaceOrderCommand == null || !flashPlaceOrderCommand.validateParams()) {
            return PlaceOrderResult.failed(INVALID_PARAMS);
        }

        AppSingleResult<FlashItemDTO> flashItemResult = flashItemAppService.getFlashItem(flashPlaceOrderCommand.getItemId());
        if (!flashItemResult.isSuccess() || flashItemResult.getData() == null) {
            return PlaceOrderResult.failed(GET_ITEM_FAILED);
        }
        FlashItemDTO flashItemDTO = flashItemResult.getData();
        if (!flashItemDTO.isOnSale()) {
            return PlaceOrderResult.failed(ITEM_NOT_ON_SALE);
        }

        String placeOrderTaskId = orderTaskIdGenerateService.generatePlaceOrderTaskId(userId, flashPlaceOrderCommand.getItemId());

        PlaceOrderTask placeOrderTask = PlaceOrderTaskBuilder.with(userId, flashPlaceOrderCommand);
        placeOrderTask.setPlaceOrderTaskId(placeOrderTaskId);
        OrderTaskSubmitResult submitResult = placeOrderTaskService.submit(placeOrderTask);

        if (!submitResult.isSuccess()) {
            return PlaceOrderResult.failed(submitResult.getCode(), submitResult.getMessage());
        }
        return PlaceOrderResult.ok(placeOrderTaskId);
    }

    @Transactional
    public void handlePlaceOrderTask(PlaceOrderTask placeOrderTask) {
        try {
            Long userId = placeOrderTask.getUserId();
            boolean isActivityAllowPlaceOrder = flashActivityDomainService.isAllowPlaceOrderOrNot(placeOrderTask.getActivityId());
            if (!isActivityAllowPlaceOrder) {
                logger.info("placeOrder|秒杀活动下单规则校验未通过:{}", userId, placeOrderTask.getActivityId());
                placeOrderTaskService.updateTaskHandleResult(placeOrderTask.getPlaceOrderTaskId(), false);
                return;
            }
            boolean isItemAllowPlaceOrder = flashItemDomainService.isAllowPlaceOrderOrNot(placeOrderTask.getItemId());
            if (!isItemAllowPlaceOrder) {
                logger.info("placeOrder|秒杀品下单规则校验未通过:{}", userId, placeOrderTask.getActivityId());
                placeOrderTaskService.updateTaskHandleResult(placeOrderTask.getPlaceOrderTaskId(), false);
                return;
            }
            FlashItem flashItem = flashItemDomainService.getFlashItem(placeOrderTask.getItemId());
            Long orderId = orderNoGenerateService.generateOrderNo(new OrderNoGenerateContext());
            FlashOrder flashOrderToPlace = toDomain(placeOrderTask);
            flashOrderToPlace.setItemTitle(flashItem.getItemTitle());
            flashOrderToPlace.setFlashPrice(flashItem.getFlashPrice());
            flashOrderToPlace.setUserId(userId);
            flashOrderToPlace.setId(orderId);

            boolean decreaseStockSuccess = flashItemDomainService.decreaseItemStock(placeOrderTask.getItemId(), placeOrderTask.getQuantity());
            if (!decreaseStockSuccess) {
                logger.info("placeOrder|库存扣减失败:{},{}", userId, JSON.toJSONString(placeOrderTask));
                return;
            }
            boolean placeOrderSuccess = flashOrderDomainService.placeOrder(userId, flashOrderToPlace);
            if (!placeOrderSuccess) {
                throw new BizException(PLACE_ORDER_FAILED.getErrDesc());
            }
            logger.info("placeOrder|下单任务完成:{},{}", userId, JSON.toJSONString(placeOrderTask));
            placeOrderTaskService.updateTaskHandleResult(placeOrderTask.getPlaceOrderTaskId(), true);
            redisCacheService.put(PLACE_ORDER_TASK_ORDER_ID_KEY + placeOrderTask.getPlaceOrderTaskId(), orderId, HOURS_24);
        } catch (Exception e) {
            placeOrderTaskService.updateTaskHandleResult(placeOrderTask.getPlaceOrderTaskId(), false);
            throw new BizException(e.getMessage());
        }
    }

    public OrderTaskHandleResult getPlaceOrderResult(Long userId, Long itemId, String placeOrderTaskId) {
        String generatedPlaceOrderTaskId = orderTaskIdGenerateService.generatePlaceOrderTaskId(userId, itemId);
        if (!generatedPlaceOrderTaskId.equals(placeOrderTaskId)) {
            return OrderTaskHandleResult.failed(PLACE_ORDER_TASK_ID_INVALID);
        }
        OrderTaskStatus orderTaskStatus = placeOrderTaskService.getTaskStatus(placeOrderTaskId);
        if (orderTaskStatus == null) {
            return OrderTaskHandleResult.failed(PLACE_ORDER_TASK_ID_INVALID);
        }
        if (!OrderTaskStatus.SUCCESS.equals(orderTaskStatus)) {
            return OrderTaskHandleResult.failed(orderTaskStatus);
        }
        Long orderId = redisCacheService.getObject(PLACE_ORDER_TASK_ORDER_ID_KEY + placeOrderTaskId, Long.class);
        return OrderTaskHandleResult.ok(orderId);
    }
}
