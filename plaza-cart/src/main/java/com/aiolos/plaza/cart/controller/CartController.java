package com.aiolos.plaza.cart.controller;

import com.aiolos.common.cloud.annotation.AnonymousAuth;
import com.aiolos.plaza.cart.dto.CartAddReq;
import com.aiolos.plaza.cart.dto.CartCheckReq;
import com.aiolos.plaza.cart.dto.CartUpdateReq;
import com.aiolos.plaza.cart.service.PlazaCartService;
import com.aiolos.plaza.cart.vo.CartListVO;
import com.aiolos.common.model.ContextInfo;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cart")
public class CartController {

    @Autowired
    private PlazaCartService plazaCartService;

    private String resolveDeviceId(String deviceId, HttpServletRequest request) {
        if (deviceId != null && !deviceId.isEmpty()) {
            return deviceId;
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("device-id".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /**
     * 加入购物车
     */
    @PostMapping("/add")
    @AnonymousAuth
    public Boolean addCart(@RequestHeader(value = "device-id", required = false) String deviceId,
                                @RequestBody CartAddReq req, HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        plazaCartService.addCart(userId, resolveDeviceId(deviceId, request), req);
        return true;
    }

    /**
     * 修改数量
     */
    @PostMapping("/update")
    @AnonymousAuth
    public Boolean updateQuantity(@RequestHeader(value = "device-id", required = false) String deviceId,
                                       @RequestBody CartUpdateReq req, HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        plazaCartService.updateQuantity(userId, resolveDeviceId(deviceId, request), req);
        return true;
    }

    /**
     * 删除商品
     */
    @PostMapping("/delete")
    @AnonymousAuth
    public Boolean deleteCartItem(@RequestHeader(value = "device-id", required = false) String deviceId,
                                       @RequestParam Long productId, HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        plazaCartService.deleteCartItem(userId, resolveDeviceId(deviceId, request), productId);
        return true;
    }

    /**
     * 勾选商品
     */
    @PostMapping("/check")
    @AnonymousAuth
    public Boolean checkCartItem(@RequestHeader(value = "device-id", required = false) String deviceId,
                                      @RequestBody CartCheckReq req, HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        plazaCartService.checkCartItem(userId, resolveDeviceId(deviceId, request), req);
        return true;
    }

    /**
     * 查询购物车
     */
    @GetMapping("/list")
    @AnonymousAuth
    public CartListVO getCartList(@RequestHeader(value = "device-id", required = false) String deviceId, HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        return plazaCartService.getCartList(userId, resolveDeviceId(deviceId, request));
    }

    /**
     * 合并购物车
     */
    @PostMapping("/merge")
    public Boolean mergeCart(@RequestHeader(value = "device-id", required = false) String deviceId, HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        plazaCartService.mergeCart(userId, resolveDeviceId(deviceId, request));
        return true;
    }

    /**
     * 清理失效商品
     */
    @PostMapping("/clearInvalid")
    @AnonymousAuth
    public Boolean clearInvalid(@RequestHeader(value = "device-id", required = false) String deviceId, HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        plazaCartService.clearInvalid(userId, resolveDeviceId(deviceId, request));
        return true;
    }
}
