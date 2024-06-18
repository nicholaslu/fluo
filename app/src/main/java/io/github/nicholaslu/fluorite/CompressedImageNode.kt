package io.github.nicholaslu.fluorite

import org.ros2.rcljava.node.BaseComposableNode
import org.ros2.rcljava.publisher.Publisher

class CompressedImageNode(nodeName: String?) : BaseComposableNode(nodeName) {
    lateinit var pub: Publisher<sensor_msgs.msg.CompressedImage>
    init {
        val topic = "pixel_1/compressed"
        pub = this.node.createPublisher(sensor_msgs.msg.CompressedImage::class.java, topic)
    }

    fun publish_msg(msg: sensor_msgs.msg.CompressedImage){
        pub.publish(msg)
    }
}