package com.aiolos.plaza.cart.service;

import com.aiolos.plaza.cart.model.bo.CartAddReq;
import com.aiolos.plaza.cart.model.bo.CartCheckReq;
import com.aiolos.plaza.cart.model.bo.CartUpdateReq;
import com.aiolos.plaza.cart.model.vo.CartListVO;

public interface PlazaCartService {
    
    /**
     * 加入购物车
     */
    void addCart(Long userId, String deviceId, CartAddReq req);
    
    /**
     * 修改数量
     */
    void updateQuantity(Long userId, String deviceId, CartUpdateReq req);
    
    /**
     * 删除商品
     */
    void deleteCartItem(Long userId, String deviceId, Long productId);
    
    /**
     * 勾选商品
     */
    void checkCartItem(Long userId, String deviceId, CartCheckReq req);
    
    /**
     * 查询购物车
     */
    CartListVO getCartList(Long userId, String deviceId);
    
    /**
     * 合并购物车
     */
    void mergeCart(Long userId, String deviceId);
    
    /**
     * 清理失效商品
     */
    void clearInvalid(Long userId, String deviceId);
}