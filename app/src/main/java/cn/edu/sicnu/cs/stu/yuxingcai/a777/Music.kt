package cn.edu.sicnu.cs.stu.yuxingcai.a777

/**
 * 音乐实体类，对应 MediaStore 中的一条音频记录
 */
data class Music(
    val id: Long,        // 音乐 ID
    val title: String,   // 歌曲名
    val artist: String,  // 歌手名
    val duration: Long,  // 时长（毫秒）
    val uri: String      // 播放地址（content:// 形式）
)
