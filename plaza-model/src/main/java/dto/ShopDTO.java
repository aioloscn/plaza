package dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ShopDTO {

    private Long id;
    private String name;
    private String iconUrl;
    private String address;
    private String tags;
    @Schema(description = "店铺位置经纬度")
    private String location;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal score;
    private Integer perCapitaPrice;
    private Long categoryId;
    private String categoryName;
    private Long sellerId;
    private BigDecimal sellerScore;
    @Schema(description = "店铺状态, 0: 禁用, 1: 启用")
    private Integer status; 
    @Schema(description = "商家状态, 0: 禁用, 1: 启用")
    private Integer sellerDisabledFlag;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
