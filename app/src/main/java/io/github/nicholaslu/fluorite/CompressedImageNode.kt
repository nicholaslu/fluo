package io.github.nicholaslu.fluorite

import org.ros2.rcljava.node.BaseComposableNode
import org.ros2.rcljava.publisher.Publisher

class CompressedImageNode(nodeName: String?) : BaseComposableNode(nodeName) {
    private lateinit var pub: Publisher<sensor_msgs.msg.CompressedImage>
    var compressedImageTopic = "compressed"
    init {
        createPub()
    }

    private fun createPub(){
        pub = this.node.createPublisher(sensor_msgs.msg.CompressedImage::class.java, compressedImageTopic)
    }

    private fun destoryPub(){
        this.node.removePublisher(pub)
    }

    fun changeTopic(topic: String, namespace: String? = null){
        val fullTopic = if (namespace == null){
            topic
        } else {
            "$namespace/$topic"
        }
        if (fullTopic != compressedImageTopic){
            compressedImageTopic = fullTopic
            destoryPub()
            createPub()
        }
    }

    fun publishCompressedImage(msg: sensor_msgs.msg.CompressedImage){
        pub.publish(msg)
    }
}