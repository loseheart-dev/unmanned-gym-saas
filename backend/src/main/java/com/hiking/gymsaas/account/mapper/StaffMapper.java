package com.hiking.gymsaas.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.hiking.gymsaas.account.model.entity.AdminUser;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 员工管理访问；商家隔离由 MerchantIsolationInterceptor 统一注入，
 * 这里不重复写 merchant_id 条件，也绝不接收请求体里的商家 ID。
 */
@Mapper
public interface StaffMapper extends BaseMapper<AdminUser> {

    String COLUMNS = "id, merchant_id, username, role, permissions, status, token_version, created_at";

    @Select("SELECT " + COLUMNS + " FROM admin_user WHERE id = #{id}")
    @Results(id = "staffRow", value = @Result(column = "permissions", property = "permissions",
            typeHandler = JacksonTypeHandler.class))
    AdminUser tenantStaff(long id);

    @Select("SELECT " + COLUMNS + " FROM admin_user WHERE id = #{id} FOR UPDATE")
    @ResultMap("staffRow")
    AdminUser lockStaff(long id);

    /** 串行锁定"本商家启用中的管理员"集合，用于最后一位管理员保护。 */
    @Select("SELECT " + COLUMNS + " FROM admin_user WHERE status = 'ACTIVE' AND role = 'ADMIN' FOR UPDATE")
    @ResultMap("staffRow")
    List<AdminUser> lockActiveAdmins();

    @Update("UPDATE admin_user SET role = #{role}, permissions = CAST(#{permissions} AS JSON), "
            + "token_version = token_version + 1 WHERE id = #{id}")
    int updateRole(@Param("id") long id, @Param("role") String role, @Param("permissions") String permissions);

    @Update("UPDATE admin_user SET status = #{status}, token_version = token_version + 1 WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") String status);

    @Update("UPDATE admin_user SET password_hash = #{hash}, token_version = token_version + 1 WHERE id = #{id}")
    int resetPassword(@Param("id") long id, @Param("hash") String hash);
}
