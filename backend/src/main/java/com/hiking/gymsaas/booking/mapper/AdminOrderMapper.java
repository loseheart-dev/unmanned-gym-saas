package com.hiking.gymsaas.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hiking.gymsaas.booking.model.entity.BookingOrder;
import org.apache.ibatis.annotations.Mapper;

/** 后台订单读取：商家隔离由拦截器注入（ADMIN/OPERATOR 身份）。 */
@Mapper
public interface AdminOrderMapper extends BaseMapper<BookingOrder> { }
