package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("dataset")
public class Dataset {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;
    private String name;
    private String originalFilename;
    private String objectKey;
    private String contentType;
    private Long fileSize;
    private String sha256;
    private String status;
    private String mappingJson;
    private String profileJson;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long rowCount;
    private Integer userCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
