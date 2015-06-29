import gov.loc.repository.bagit.Bag;
import gov.loc.repository.bagit.BagFactory;
import gov.loc.repository.bagit.BagFile;

import java.io.File;

public class BagitTest {
    public static void main(String[] args) {
        BagFactory bagFactory = new BagFactory();
        File bagFile = new File("test-resources/example-bag2.zip");
        Bag bag = bagFactory.createBag(bagFile, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS);
        System.out.println(bag.getFile());
        System.out.println(bag.getFormat());
        System.out.println(bag.getVersion());

        System.out.println("PAYLOAD FILES:");
        for (BagFile file : bag.getPayload()) {
            System.out.println(file.getFilepath());
        }
        System.out.println("TAG FILES:");
        for (BagFile tag: bag.getTags()) {
            System.out.println(tag.getFilepath());
        }
//        System.out.println(bag.verifyComplete());
//        System.out.println(bag.verifyPayloadManifests());
        System.out.println(bag.verifyValid());

        System.out.println(bag);

    }
}
