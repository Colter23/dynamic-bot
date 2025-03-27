package top.colter.dynamic.draw

import top.colter.dynamic.data.Publisher
import top.colter.dynamic.draw.component.Author
import top.colter.dynamic.draw.component.AuthorSmall
import top.colter.skiko.*
import top.colter.skiko.layout.Layout


fun Layout.drawPublisher(publisher: Publisher, time: String) {

    if (containsEnv("forward")) {
        AuthorSmall(
            face = publisher.face.image?.makeImage()!!,
            official = publisher.official,
            name = publisher.name,
            time = time,
            modifier = Modifier().fillMaxWidth().height(50.dp).margin(horizontal = 5.dp, vertical = 10.dp) // .background(Color.RED)
        )
    } else {
        Author(
            face = publisher.face.image?.makeImage()!!,
            pendant = publisher.pendant?.image?.makeImage()!!,
            official = publisher.official,
            name = publisher.name,
            time = time,

            modifier = Modifier().fillMaxWidth().height(100.dp).margin(horizontal = (-15).dp, vertical = 10.dp) // .background(Color.RED)
//                modifier = Modifier().fillMaxWidth().height(100.dp).margin(top = 10.dp, right = (-15).dp, bottom = 30.dp, left = (-15).dp) // .background(Color.RED)
        )
    }

}
